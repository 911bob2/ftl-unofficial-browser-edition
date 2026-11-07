package xyz.znix.xftl.game

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.ui.ImageView
import xyz.znix.xftl.ui.Label
import xyz.znix.xftl.ui.WidgetContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * This contains the drag-and-drop equipment UI used in the equipment tab
 * of the player ship UI, and in the sell tab of the store.
 */
class ShipEquipmentPanel(private val game: InGameState, val ship: Ship) {

    var position: IPoint = ConstPoint.ZERO
        set(value) {
            if (field == value)
                return
            field = value
            updateButtons()
        }

    var sellUI: Boolean = false

    /**
     * Optional placement override for the sell drop box (sell UI only).
     * Called with the box's measured size once it's laid out; returns its
     * window-space position. Null (or a null return) keeps the vanilla
     * spot left of the window. The touch store uses this because the
     * vanilla spot is thrown off-screen by the window group's leftward
     * shift.
     */
    var sellBoxPlacer: ((IPoint) -> IPoint?)? = null

    /** Where [sellButton] sits, in panel-local coordinates. */
    private var sellBoxLocalPos: IPoint = ConstPoint(-275, 107)

    private val sectionFont = game.getFont("HL2", 2f)
    private val missingSystemFont = game.getFont("HL2", 3f)
    private val sellBoxFont = game.getFont("HL1", 2f)
    private val sellPriceFont = game.getFont("num_font")
    private val augmentFont = game.getFont("JustinFont12Bold")
    private val augmentLabelFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 13)

    /**
     * Equipment::SetPosition (0x00539430): weapon and drone boxes 117px apart from
     * x=63, moved 58px right per missing slot of four (-7 for this panel's origin).
     */
    private fun slotX(slots: Int, i: Int) = 56 + (4 - slots) * 58 + i * 117

    private val sellSound = game.sounds.getSample("sell")

    private val buttons = ArrayList<Button>()
    private val weaponButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private val droneButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private val augmentButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private val cargoButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private var sellButton: Button? = null // The box for selling and leaving behind items
    // The over-capacity boxes (outside the store) and the cargo slot each cargo button stands for.
    private val overCapacityBoxes = ArrayList<OverCapacityBox>()
    private val cargoSlots = HashMap<Button, Int>()

    // Hyperspace (CustomEquipment.cpp): the augment and cargo pages, the augment index each shown box stands for, the
    // dummy boxes of a missing weapon or drone system, and the paging arrows.
    private val hsUi: Boolean get() = ship.hs != null && xyz.znix.xftl.hs.HS.active
    private var hsAugPage = 0
    private var hsCargoPage = 0
    private val augmentIndex = HashMap<Button, Int>()
    private val hsDummies = ArrayList<Triple<Int, Int, String>>()
    private var hsAugPageCount = 1
    private var hsCargoPageCount = 1

    // Touch layout: a tap selects a box (shown like a hovered box, with its info). Pressing the selected box again and
    // moving over 9 px picks the item up, with the item's middle following the finger from where it was; that middle is
    // what the drop looks at. The selection is kept as the box's place, since the boxes are remade when the ship changes.
    private var touchSelectedPos: IPoint? = null
    private var touchCandidate: Buttons.DragDropBlueprintButton? = null
    private var touchStart: IPoint = ConstPoint.ZERO
    private var touchDragOrigin: IPoint = ConstPoint.ZERO
    private var touchInfoX = Int.MAX_VALUE

    /** The store's sell tab: selling the selected item, in place of dragging it to a drop box. */
    private val touchSell by lazy {
        TouchGetBox(
            game, game.translator["button_sell"],
            { touchSelectedButton()?.blueprint?.let { (it.cost ?: 0) / 2 } },
            { touchSelectedButton()?.let { !hsLocked(it.blueprint) } == true },
            ::touchSellSelected
        )
    }

    private fun touchSelectedButton(): Buttons.DragDropBlueprintButton? {
        val selected = touchSelectedPos ?: return null
        return buttons.firstOrNull {
            it is Buttons.DragDropBlueprintButton && it.blueprint != null &&
                it.basePos.x == selected.x && it.basePos.y == selected.y
        } as Buttons.DragDropBlueprintButton?
    }

    private fun touchSellSelected() {
        val selected = touchSelectedButton() ?: return
        val sell = sellButton ?: return
        selected.callback()
        if (draggingBlueprint == null) return
        dropBlueprint(sell)
        touchSelectedPos = null
    }

    private fun touchPress(button: Int, x: Int, y: Int) {
        touchCandidate = null
        if (button != Input.MOUSE_LEFT_BUTTON) return
        if (sellUI && touchSell.press(x, y)) return

        for (btn in ArrayList(buttons)) {
            if (btn !is Buttons.DragDropBlueprintButton) btn.mouseDown(button, x, y)
        }

        val box = buttons.lastOrNull { it is Buttons.DragDropBlueprintButton && it.contains(x, y) } as Buttons.DragDropBlueprintButton?
        when {
            // The info column takes touches without touching the selection.
            box == null && sellUI && x >= touchInfoX -> Unit
            box == null || box.blueprint == null -> touchSelectedPos = null
            touchSelectedButton() === box -> {
                touchCandidate = box
                touchStart = ConstPoint(x, y)
            }
            else -> touchSelectedPos = box.basePos
        }
    }

    private fun touchMove(x: Int, y: Int) {
        val candidate = touchCandidate
        if (candidate != null) {
            val dx = x - touchStart.x
            val dy = y - touchStart.y
            if (dx * dx + dy * dy > TOUCH_DRAG_DIST * TOUCH_DRAG_DIST) {
                touchCandidate = null
                candidate.callback() // A locked augment stays where it is.
                touchDragOrigin = candidate.pos + touchGrabPoint(candidate)
            }
        }

        val dragging = draggingBlueprint
        if (dragging != null) {
            val hitX = touchDragOrigin.x + x - touchStart.x
            val hitY = touchDragOrigin.y + y - touchStart.y
            // Augments are drawn from their corner, everything else from its middle.
            dragging.dragPosition = if (dragging.blueprint is AugmentBlueprint) {
                val grab = touchGrabPoint(dragging)
                ConstPoint(hitX - grab.x, hitY - grab.y)
            } else {
                ConstPoint(hitX, hitY)
            }
            for (button in buttons) button.update(hitX, hitY)
            return
        }

        val selected = touchSelectedButton()
        for (button in buttons) {
            when {
                button !is Buttons.DragDropBlueprintButton -> button.update(x, y)
                button === selected -> button.update(button.pos.x + 1, button.pos.y + 1)
                else -> button.update(-1, -1)
            }
        }
        if (sellUI) touchSell.updateHover(x, y)
    }

    private fun touchGrabPoint(box: Buttons.DragDropBlueprintButton): IPoint =
        if (box.blueprint is AugmentBlueprint) ConstPoint(117, 20) else ConstPoint(50, 33)

    private fun touchRelease() {
        touchCandidate = null
        val dragging = draggingBlueprint ?: return
        val item = dragging.blueprint
        val from = dragging.basePos
        val to = buttons.firstOrNull { it is Buttons.DragDropBlueprintButton && it.hovered }?.basePos
        dropBlueprint()

        // The selection follows the item to the box it landed in.
        val landed = to != null && buttons.any {
            it is Buttons.DragDropBlueprintButton && it.blueprint === item && it.basePos.x == to.x && it.basePos.y == to.y
        }
        touchSelectedPos = if (landed) to else from
    }

    /** The touch layout's store sell tab: the over-capacity items sit in its info column, without the boxes' texts. */
    private val touchSellTab: Boolean get() = sellUI && TouchMode.enabled

    /**
     * Where the over-capacity box goes: the touch layout has no room left of the window, so it uses the info column below
     * the info box, as the iPad game does (both boxes at (600, 320), under its info box at (600, -25); ours sits at our
     * info box's place).
     */
    private val overBoxPos: ConstPoint get() = if (TouchMode.enabled) ConstPoint(621, 330) else ConstPoint(-275 - 7, 100)

    /**
     * Touch layout: the over-capacity box shown while both are in use. The original and the iPad draw the item box and
     * the augment box at one place, the augment box over the item box (Equipment::OnRender 0x53a9f0), so neither can be
     * read; the touch layout shows one at a time with Hyperspace's over-capacity page arrows (the multipleOverCapacity
     * pager), turning to the box that took an item last. 4 the item box, 5 the augment box.
     */
    private var touchOverSlot = ship.overCapacityLastSlot
    private var touchOverAdds = ship.overCapacityAdds

    /** Both over-capacity boxes are in use and the touch layout pages between them. */
    private val touchOverPaged: Boolean get() = TouchMode.enabled && !touchSellTab && overCapacityShown(4) && overCapacityShown(5)

    /** Whether the over-capacity box of [slot] (4 or 5) is drawn, with its item. */
    private fun overBoxDrawn(slot: Int): Boolean = overCapacityShown(slot) && (!touchOverPaged || touchOverSlot == slot)

    /** The touch pager's other box. */
    fun touchTurnOverPage() {
        if (!touchOverPaged) return
        touchOverSlot = if (touchOverSlot == 4) 5 else 4
        touchSelectedPos = null
        updateButtons()
    }

    private var draggingBlueprint: Buttons.DragDropBlueprintButton? = null
        set(value) {
            field = value
            for (button in buttons) {
                if (button !is Buttons.DragDropBlueprintButton)
                    continue
                button.currentlyDraggedBlueprint = value?.blueprint
            }
        }

    // Note: the buttons are first populated when the position is set

    fun draw(g: Graphics) {
        sectionFont.drawString(
            position.x + 11f,
            position.y + 60f,
            game.translator["equipment_frame_weapons"],
            Constants.JUMP_DISABLED_TEXT
        )
        sectionFont.drawString(
            position.x + 11f,
            position.y + 170f,
            game.translator["equipment_frame_drones"],
            Constants.JUMP_DISABLED_TEXT
        )
        sectionFont.drawString(
            position.x + 11f,
            position.y + 280f,
            game.translator["equipment_frame_cargo"],
            Constants.JUMP_DISABLED_TEXT
        )
        sectionFont.drawString(
            position.x + 300f,
            position.y + 280f,
            game.translator["equipment_frame_augments"],
            Constants.JUMP_DISABLED_TEXT
        )

        if (ship.drones == null) {
            missingSystemFont.drawString(
                position.x + 108f,
                position.y + 227f,
                game.translator["equipment_no_system"],
                Constants.SECTOR_CUTOUT_TEXT
            )
        }
        if (hsUi) {
            // CustomEquipment::OnRender: the missing system's dummy boxes at the configured opacity, and the page counters.
            if (ship.weapons == null) missingSystemFont.drawString(position.x + 108f, position.y + 117f, game.translator["equipment_no_system"], Constants.SECTOR_CUTOUT_TEXT)
            for ((x, y, img) in hsDummies) game.getImg(img).draw(position.x + x, position.y + y, Colour(1f, 1f, 1f, xyz.znix.xftl.hs.HS.cfg().dummyEquipmentSlotsOpacity))
            if (hsAugPageCount > 1) {
                val t = "${hsAugPage + 1}/$hsAugPageCount"
                sectionFont.drawString(position.x + 575f - sectionFont.getWidth(t), position.y + 280f, t, Constants.JUMP_DISABLED_TEXT)
            }
            if (hsCargoPageCount > 1) {
                val t = "${hsCargoPage + 1}/$hsCargoPageCount"
                sectionFont.drawString(position.x + 281f - sectionFont.getWidth(t), position.y + 280f, t, Constants.JUMP_DISABLED_TEXT)
            }
        }

        if (touchSellTab && ship.cargoBlueprints.drop(4).any { it != null }) sectionFont.drawString(position.x + (if (TouchMode.enabled) 646f else -250f), position.y + (if (TouchMode.enabled) 360f else 280f), "OVER CAPACITY", Constants.JUMP_DISABLED_TEXT)
        for (button in buttons) {
            button.draw(g)
        }
    }


    /** EquipmentBox for an augment (box_augment art, name in font 13); also the over-capacity augment box. */
    /** CustomAugments.cpp `locked`: such an augment is not moved, and shows a padlock beside its box. */
    private fun hsLocked(bp: Blueprint?): Boolean = hsUi && bp is AugmentBlueprint && xyz.znix.xftl.hs.ship.HsAugments.isLocked(bp.name)

    private fun augmentButton(buttonPos: IPoint, augment: Blueprint?, overBox: Boolean = false): Buttons.DragDropBlueprintButton {
        val size = ConstPoint(235, 40)
        // Use a separate variable so we can use the button in its callback.
        lateinit var button: Buttons.DragDropBlueprintButton
        button = object : Buttons.DragDropBlueprintButton(
            buttonPos, game, null, size,
            { it is AugmentBlueprint },
            augment,
            { if (!hsLocked(button.blueprint)) draggingBlueprint = button }
        ) {
            override fun draw(g: Graphics) {
                if (hsLocked(blueprint) && dragPosition == null) game.getImg("img/upgradeUI/Equipment/aug_lock.png").draw(pos.x - (if (overBox) 36 else 24), pos.y + 7)
                // EquipmentBox::OnRender (0x00540150): the box_augment art, 'off' while
                // empty (or while its augment is dragged away), 'selected' variants on hover.
                val filled = blueprint != null && dragPosition == null
                val state = when {
                    filled && hovered -> "selected"
                    filled -> "on"
                    hovered -> "off_selected"
                    else -> "off"
                }
                game.getImg("img/upgradeUI/Equipment/box_augment_$state.png").draw(pos)

                // RenderLabels: font 13 centred at (117,14), white.
                val aug = blueprint as AugmentBlueprint?
                if (aug != null && dragPosition == null)
                    augmentLabelFont.printCenter(pos.x + 117f, pos.y + 14f, aug.translateTitle(game), xyz.znix.xftl.rendering.Colour.white)
            }

            override fun drawDrag(g: Graphics) {
                drawAugment(g, dragPosition ?: return)
            }

            private fun drawAugment(g: Graphics, pos: IPoint) {
                val aug = blueprint as AugmentBlueprint? ?: return

                // Draw the borders. Since the middle is semi-transparent, we can't
                // just fill in the whole thing twice to get our border easily.
                g.colour = when {
                    dragPosition != null -> Constants.AUGMENT_BOX_OUTLINE
                    hovered -> Constants.AUGMENT_BOX_OUTLINE_HOVER
                    else -> Constants.AUGMENT_BOX_OUTLINE
                }
                // Left and right
                g.fillRect(pos.x + 0f, pos.y + 0f, 3f, size.y.f)
                g.fillRect(pos.x + size.x - 3f, pos.y + 0f, 3f, size.y.f)

                // Top and bottom
                g.fillRect(pos.x + 3f, pos.y + 0f, size.x - 6f, 3f)
                g.fillRect(pos.x + 3f, pos.y + size.y - 3f, size.x - 6f, 3f)

                // Fill in the background
                g.colour = Constants.AUGMENT_BOX_INSIDE
                g.fillRect(pos.x + 3f, pos.y + 3f, size.x - 6f, size.y - 6f)

                // Draw the name
                val name = aug.translateTitle(game)
                augmentFont.drawStringCentred(pos.x.f, pos.y.f + 27f, size.x.f, name, Constants.AUGMENT_NAME_TEXT)
            }
        }
        return button
    }

    /** Diagnostics (WP2 probe): the paging state of the Hyperspace equipment screen. */
    val diagnosticState: String get() = "{\"augPage\":$hsAugPage,\"augPages\":$hsAugPageCount,\"cargoPage\":$hsCargoPage,\"cargoPages\":$hsCargoPageCount," +
        "\"over\":${xyz.znix.xftl.hs.ship.HsEquipment.overCount(ship)},\"buttons\":${buttons.size}}"

    /**
     * Tests (ShipWindow.diagnosticOverCapacity): the over-capacity boxes drawn (kind, place relative to [origin], 272 x
     * DropBox::GetHeight, the item box in it), how many are in use (the touch pager's pages) and the pager's arrows.
     */
    fun diagnosticOverCapacity(origin: IPoint): String {
        fun r(x: Int, y: Int, w: Int, h: Int) = "{\"x\":${x - origin.x},\"y\":${y - origin.y},\"w\":$w,\"h\":$h}"
        val boxes = overCapacityBoxes.joinToString(",") { box ->
            val slot = if (box.augmentBox) 5 else 4
            val item = cargoButtons.firstOrNull { cargoSlots[it] == slot }
            "{\"kind\":\"${if (box.augmentBox) "augment" else "item"}\",\"x\":${box.pos.x - origin.x},\"y\":${box.pos.y - origin.y}," +
                "\"w\":${box.size.x},\"h\":${box.size.y}" + (if (item != null) ",\"slot\":${r(item.pos.x, item.pos.y, item.size.x, item.size.y)}" else "") + "}"
        }
        val inUse = if (touchSellTab) 0 else if (xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)) minOf(1, overCapacityBoxes.size) else (4..5).count { overCapacityShown(it) }
        val arrows = buttons.filterIsInstance<ArrowButton>().filter { it.overPager }.joinToString(",") { r(it.pos.x, it.pos.y, it.size.x, it.size.y) }
        return "\"over\":[$boxes],\"overPages\":$inUse,\"overArrows\":[$arrows]"
    }

    fun drawDrag(g: Graphics) {
        // Draw the dragged bit of equipment above everything else
        draggingBlueprint?.drawDrag(g)
    }

    fun shipModified() {
        updateButtons()
    }

    /** Tests: the boxes made again for the current layout (TouchMode.enabled). */
    fun diagnosticRelayout() = updateButtons()

    /** Diagnostics: drag the [weapon]th weapon box onto the [cargo]th ordinary cargo box and drop it, as the mouse does. */
    internal fun diagnosticDragWeaponToCargo(weapon: Int, cargo: Int) {
        updateButtons()
        val from = weaponButtons[weapon]
        val to = cargoButtons.first { cargoSlots[it] == cargo }
        draggingBlueprint = from
        dropBlueprint(to)
        updateButtons()
    }

    private fun updateButtons() {
        buttons.clear()

        // Draw the weapons
        // Note: I think all the playable ships have weaponSlots set?
        weaponButtons.clear()
        hsDummies.clear()
        val hsNoWeapons = hsUi && ship.weapons == null
        val weaponSlotCount = ship.weaponSlots ?: 4
        if (hsNoWeapons) {
            // CustomEquipment::OnRender: without a weapon system the boxes are not drawn, only (showDummyEquipmentSlots) faintly.
            if (xyz.znix.xftl.hs.HS.cfg().showDummyEquipmentSlots)
                for (i in 0 until weaponSlotCount) hsDummies += Triple(slotX(weaponSlotCount, i), 70, "img/upgradeUI/Equipment/box_weapons_off.png")
        }
        for (i in 0 until (if (hsNoWeapons) 0 else weaponSlotCount)) {
            val weapon = ship.hardpoints[i].weapon

            val images = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_weapons", true)
            val buttonPos = ConstPoint(slotX(weaponSlotCount, i), 70)

            // Use a separate variable so we can use the button in its callback.
            lateinit var button: Buttons.DragDropBlueprintButton
            button = Buttons.DragDropBlueprintButton(
                buttonPos, game, images,
                { it is AbstractWeaponBlueprint },
                weapon?.type
            ) {
                draggingBlueprint = button
            }
            buttons += button
            weaponButtons += button
        }

        // Draw the drones
        droneButtons.clear()
        val droneSystem = ship.drones
        if (droneSystem == null && hsUi && xyz.znix.xftl.hs.HS.cfg().showDummyEquipmentSlots) {
            val n = ship.droneSlots ?: 2
            for (i in 0 until n) hsDummies += Triple(slotX(n, i), 180, "img/upgradeUI/Equipment/box_drones_off.png")
        }
        if (droneSystem != null) {
            val images = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_drones", true)

            for ((i, droneInfo) in droneSystem.drones.withIndex()) {
                val buttonPos = ConstPoint(slotX(droneSystem.drones.size, i), 180)
                val drone = droneInfo?.type

                // Use a separate variable so we can use the button in it's callback.
                lateinit var button: Buttons.DragDropBlueprintButton
                button = Buttons.DragDropBlueprintButton(buttonPos, game, images, { it is DroneBlueprint }, drone) {
                    draggingBlueprint = button
                }
                buttons += button
                droneButtons += button
            }
        }

        // Draw the augments area. ShipInfo::augList is a std::map: Equipment fills the boxes in blueprint-name order,
        // whatever order the augments came in (oracle sw2-ship q1, HS leaves the native Equipment boxes alone).
        if (ship.augments.zipWithNext().any { (a, b) -> a.name > b.name }) ship.augments.sortBy { it.name }
        augmentButtons.clear()
        augmentIndex.clear()
        val augTotal = ship.augmentSlotCount
        hsAugPageCount = xyz.znix.xftl.hs.render.HsUpgrades.pages(augTotal, 3)
        hsAugPage = hsAugPage.coerceIn(0, hsAugPageCount - 1)
        for (i in (if (hsUi) hsAugPage * 3 else 0) until (if (hsUi) minOf(augTotal, hsAugPage * 3 + 3) else 3)) {
            val button = augmentButton(ConstPoint(323, 293 + (i % 3) * 60), ship.augments.getOrNull(i))
            buttons += button
            augmentButtons += button
            augmentIndex[button] = i
        }

        // Equipment::SetPosition (0x539430): outside the store each overflow slot has its own over-capacity
        // DropBox at (-275, 100) (7 px left in this panel's frame, like every box), drawn (Equipment::OnRender 0x53a9f0) before the slots and only while that
        // slot is in use: the item box (item_text1, 100 px, item_text2) with the cargo box 80 px in, and the
        // augment box (aug_text1, 65 px, aug_text2) with an augment box 19 px in and 4 px lower, both at the
        // DropBox body offset (DropBox::GetBodySpaceOffset 0x4e80c0: title and text1 + 65). A box stays, emptied,
        // until the next jump once something was put in it (Equipment +0x3e0/+0x3e1, Ship.overCapacityBoxShown).
        // The store's sell tab draws the same boxes (Equipment::OnRender checks bOverCapacity/bOverAugCapacity, not
        // bStoreMode; oracle bt-store-over 05-store-sell); only the touch sell tab keeps them in its info column.
        cargoButtons.clear()
        cargoSlots.clear()
        overCapacityBoxes.clear()
        if (!touchSellTab) {
            if (touchOverAdds != ship.overCapacityAdds) { touchOverAdds = ship.overCapacityAdds; touchOverSlot = ship.overCapacityLastSlot }
            if (touchOverSlot !in 4..5 || !overCapacityShown(touchOverSlot)) touchOverSlot = if (overCapacityShown(5) && !overCapacityShown(4)) 5 else 4
            if (overBoxDrawn(4))
                overCapacityBoxes += OverCapacityBox("overcapacity_item_title", "overcapacity_item_text1", 100, "overcapacity_item_text2")
            if (overBoxDrawn(5))
                overCapacityBoxes += OverCapacityBox("overcapacity_aug_title", "overcapacity_aug_text1", 65, "overcapacity_aug_text2")
            buttons += overCapacityBoxes
        }

        // Draw the cargo area. [i] is the slot id (0-3 the first boxes, 4 and 5 the over-capacity boxes, 100 + n Hyperspace's
        // extra cargo slot 4 + n); [ordinal] the box's place among the ordinary ones.
        val cargoTotal = ship.cargoSlotCount
        hsCargoPageCount = xyz.znix.xftl.hs.render.HsUpgrades.pages(cargoTotal, 4)
        hsCargoPage = hsCargoPage.coerceIn(0, hsCargoPageCount - 1)
        val cargoIds = ArrayList<Int>()
        if (hsUi) {
            for (j in hsCargoPage * 4 until minOf(cargoTotal, hsCargoPage * 4 + 4)) cargoIds += if (j < 4) j else 100 + (j - 4)
        } else {
            cargoIds += listOf(0, 1, 2, 3)
        }
        cargoIds += listOf(4, 5)
        for (i in cargoIds) {
            if (i in 4..5 && (if (touchSellTab) ship.cargoBlueprints.drop(4).all { it == null } else !overBoxDrawn(i))) continue
            val blueprint = slotGet(i)
            val ordinal = if (i >= 100) i - 100 + 4 else i

            // The over-capacity augment is an AugmentEquipBox wherever it shows, the store included: a weapon/drone box
            // cannot draw an augment.
            if (i == 5) {
                val augmentPos = if (touchSellTab) ConstPoint(646, 373 + 80) else {
                    val box = overCapacityBoxes.last()
                    ConstPoint(box.basePos.x + 19, box.basePos.y + box.bodySpaceOffset + 4)
                }
                val augmentBox = augmentButton(augmentPos, blueprint, true)
                buttons += augmentBox
                cargoButtons += augmentBox
                cargoSlots[augmentBox] = i
                continue
            }

            val weapons = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_weapons", true)
            val drones = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_drones", true)

            val base = ButtonImageSet(
                game.getImg("img/upgradeUI/Equipment/box_base_off.png"),
                game.getImg("img/upgradeUI/Equipment/box_base_off.png"), // There's no 'on' image
                game.getImg("img/upgradeUI/Equipment/box_base_off_selected.png")
            )

            val itemBox = overCapacityBoxes.firstOrNull()
            val buttonPos = if (i == 4 && !touchSellTab && itemBox != null)
                ConstPoint(itemBox.basePos.x + 80, itemBox.basePos.y + itemBox.bodySpaceOffset)
            else if (i in 4..5) (if (TouchMode.enabled) ConstPoint(646, 373 + 80 * (i - 4)) else ConstPoint(-250, 293 + 80 * (i - 4))) else ConstPoint(
                23 + 130 * (ordinal % 2),
                293 + 80 * ((ordinal % 4) / 2)
            )

            // Use a separate variable so we can use the button in it's callback.
            lateinit var button: Buttons.DragDropBlueprintButton
            button = object : Buttons.DragDropBlueprintButton(
                buttonPos, game, base,
                { if (i == 5) it is AugmentBlueprint else it !is AugmentBlueprint },
                blueprint,
                { draggingBlueprint = button }) {

                // Change the card image depending on our contents
                override val image: ButtonImageSet
                    get() {
                        // If our contents is being dragged, don't keep the weapon/drone
                        // icon on the card.
                        if (dragPosition != null)
                            return base

                        return when (this.blueprint) {
                            is AbstractWeaponBlueprint -> weapons
                            is DroneBlueprint -> drones
                            else -> base
                        }
                    }
            }
            buttons += button
            cargoButtons += button
            cargoSlots[button] = i
        }

        // Draw the sell/leave behind box - do this here since
        // the sell and leave behind (when you get a piece of
        // equipment but your cargo is full) UIs are similar.
        sellButton = null
        if (sellUI) {
            // Created at local (0,0): the laid-out widget's size is only
            // known after construction, and the placer positions it from
            // that size. Re-anchored after the generic windowOffset loop
            // below.
            sellButton = SellDropBox.create(
                game,
                SellDropBox.Type.SELL_EQUIPMENT,
                ConstPoint(0, 0),
                { draggingBlueprint?.blueprint },
                { getHoveredBlueprint() }
            )

            // Hyperspace's CustomEquipment::OnRender moves the sell box below the shown over-capacity box (the item
            // box before the augment box); in the original the over-capacity box covers it.
            val shownOverBox = overCapacityBoxes.firstOrNull()
            sellBoxLocalPos = sellBoxPlacer?.invoke(sellButton!!.size)
                ?: if (hsUi && shownOverBox != null) ConstPoint(-275, shownOverBox.basePos.y + shownOverBox.size.y + 2) else ConstPoint(-275, 107)

            // The touch layout sells with a button instead, by the info box. Drawn first, under an over-capacity box,
            // and found first by a drop: in the store a drop on the sell box sells (Equipment::MouseUp, bSellingItem).
            if (!TouchMode.enabled) buttons.add(0, sellButton!!)
        }

        if (hsUi) {
            // CustomEquipment's paging buttons (Button::OnInit images and locations, 7 px left for this panel's frame).
            if (hsAugPageCount > 1) {
                buttons += ArrowButton("img/statusUI/button_crew_up", ConstPoint(564, 339), false) { hsAugPage = (hsAugPage + hsAugPageCount - 1) % hsAugPageCount; updateButtons() }
                buttons += ArrowButton("img/statusUI/button_crew_down", ConstPoint(564, 394), false) { hsAugPage = (hsAugPage + 1) % hsAugPageCount; updateButtons() }
            }
            if (hsCargoPageCount > 1) {
                buttons += ArrowButton("img/upgradeUI/buttons_system_arrow", ConstPoint(9, 369), false) { hsCargoPage = (hsCargoPage + hsCargoPageCount - 1) % hsCargoPageCount; updateButtons() }
                buttons += ArrowButton("img/upgradeUI/buttons_system_arrow", ConstPoint(271, 369), true) { hsCargoPage = (hsCargoPage + 1) % hsCargoPageCount; updateButtons() }
            }
            if (xyz.znix.xftl.hs.ship.HsEquipment.overCount(ship) > 1) {
                buttons += ArrowButton("img/upgradeUI/Equipment/button_crew_arrow", ConstPoint(overBoxPos.x + 180, overBoxPos.y - 30), false, true) { xyz.znix.xftl.hs.ship.HsEquipment.turnOverPage(ship, false); updateButtons() }
                buttons += ArrowButton("img/upgradeUI/Equipment/button_crew_arrow", ConstPoint(overBoxPos.x + 215, overBoxPos.y - 30), true, true) { xyz.znix.xftl.hs.ship.HsEquipment.turnOverPage(ship, true); updateButtons() }
            }
        }
        // The touch pager between the item and the augment box, right-aligned over the box like the multipleOverCapacity
        // arrows. Hyperspace's arrow art is the original's customizeUI/button_arrow, pixel for pixel; touch uses that
        // art's iPad version (img/ipad/customizeUI/button_arrow, 47 x 41), a finger's size.
        if (touchOverPaged) {
            val art = "img/ipad/customizeUI/button_arrow"
            buttons += ArrowButton(art, ConstPoint(overBoxPos.x + 175, overBoxPos.y - 44), false, true) { touchTurnOverPage() }
            buttons += ArrowButton(art, ConstPoint(overBoxPos.x + 225, overBoxPos.y - 44), true, true) { touchTurnOverPage() }
        }

        for (button in buttons) {
            button.windowOffset = position
        }

        // Re-anchor the sell box after the generic loop above: it was
        // created at local (0,0), so its offset is position + local pos.
        if (sellUI)
            sellButton!!.windowOffset = position + sellBoxLocalPos
    }

    fun updateUI(x: Int, y: Int) {
        if (TouchMode.enabled) {
            touchMove(x, y)
            return
        }

        for (button in buttons) {
            button.update(x, y)
        }

        // If we're dragging a blueprint around, update it.
        draggingBlueprint?.dragPosition = ConstPoint(x, y)
    }

    /**
     * The panel button under a window-space point, for the touch
     * tap-to-arm gate (Window.clickTargetAt). Identity of the returned
     * instance is irrelevant - the gate only needs to know a target
     * exists here; the armed check is done against the armed button's
     * own (stable) rect.
     */
    fun buttonAt(x: Int, y: Int): Button? = buttons.firstOrNull { it.contains(x, y) }

    fun mouseClick(button: Int, x: Int, y: Int) {
        if (TouchMode.enabled) {
            touchPress(button, x, y)
            return
        }

        // Copied from Window.
        // Mouse clicking may change the buttons array (eg in the store
        // window when switching tabs), so copy it.
        for (btn in ArrayList(buttons)) {
            btn.mouseDown(button, x, y)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun mouseReleased(button: Int, x: Int, y: Int) {
        if (TouchMode.enabled) {
            if (button == Input.MOUSE_LEFT_BUTTON) touchRelease()
            return
        }

        if (draggingBlueprint != null)
            dropBlueprint()
    }

    /** CustomEquipment::OnScrollWheel: the wheel over the cargo or augment area turns that page ([change] < 0 = next page). */
    fun mouseScroll(change: Int, mx: Int, my: Int) {
        if (!hsUi || sellUI) return
        val x = mx - position.x
        val y = my - position.y
        if (y < 264 || y >= 264 + 199) return
        fun turn(page: Int, count: Int) = if (change < 0) (page + 1) % count else (page + count - 1) % count
        if (x in 3 until 3 + 286 && hsCargoPageCount > 1) { hsCargoPage = turn(hsCargoPage, hsCargoPageCount); updateButtons() }
        if (x in 293 until 293 + 290 && hsAugPageCount > 1) { hsAugPage = turn(hsAugPage, hsAugPageCount); updateButtons() }
    }

    fun getHoveredBlueprint(): Blueprint? {
        // Don't show anything while a blueprint is being dragged.
        if (draggingBlueprint != null)
            return null

        if (TouchMode.enabled) return touchSelectedButton()?.blueprint

        val hoveredButton = buttons.firstOrNull { it.hovered }
        if (hoveredButton !is Buttons.DragDropBlueprintButton)
            return null

        return hoveredButton.blueprint
    }

    fun drawInfoPanel(infoPanel: InfoPanel) {
        if (!TouchMode.enabled) {
            drawHoveredInfo(infoPanel)
            return
        }

        touchInfoX = infoPanel.position.x
        infoPanel.touchBottom = 0
        drawHoveredInfo(infoPanel)
    }

    /** The sell tab's SELL box, below the info [drawInfoPanel] just drew. */
    fun drawTouchSell(g: Graphics, infoPanel: InfoPanel) {
        if (getHoveredBlueprint() != null) touchSell.drawBelow(g, infoPanel) else touchSell.hide()
    }

    private fun drawHoveredInfo(infoPanel: InfoPanel) {
        val blueprint = getHoveredBlueprint() ?: return
        return when (blueprint) {
            is DroneBlueprint -> infoPanel.drawDrone(blueprint)
            is AugmentBlueprint -> infoPanel.drawAugment(blueprint)
            is AbstractWeaponBlueprint -> infoPanel.drawWeapon(blueprint)
            else -> error("Cannot draw info panel for $blueprint")
        }
    }

    private fun dropBlueprint(target: Button? = null) {
        val drag = draggingBlueprint!!
        drag.dragPosition = null
        draggingBlueprint = null

        data class SlotAccess(
            val get: () -> Blueprint?,
            val set: (Blueprint?) -> Unit
        )

        fun getAccess(target: Button): SlotAccess? {
            // Check the weapons
            for ((i, button) in weaponButtons.withIndex()) {
                if (button != target)
                    continue

                return SlotAccess(
                    { ship.hardpoints[i].weapon?.type },
                    { bp ->
                        val weapon = (bp as? AbstractWeaponBlueprint)?.buildInstance(ship)
                        if (weapon == null) removeWeaponClosingGap(i) else ship.hardpoints[i].weapon = weapon
                    }
                )
            }

            // Check the drone system
            for ((i, button) in droneButtons.withIndex()) {
                if (button != target)
                    continue

                val drones = ship.drones ?: break

                return SlotAccess(
                    { drones.drones[i]?.type },
                    { drone ->
                        // If the current slot contains an already-deployed drone, get rid of it.
                        drones.drones[i]?.instance?.removeInstance()

                        drones.drones[i] = drone?.let { Drones.DroneInfo(it as DroneBlueprint, null) }
                    }
                )
            }

            // Check the augments
            for (button in augmentButtons) {
                if (button != target)
                    continue
                val i = augmentIndex.getValue(button)

                // Grab the augment here, in case the ordering changes
                val aug = ship.augments.getOrNull(i)

                return SlotAccess(
                    { aug },
                    {
                        require(it is AugmentBlueprint?)
                        if (aug != null) {
                            require(ship.augments.remove(aug)) { "Augment '${aug.name}' disappeared while dragging!" }
                        }
                        if (it != null) {
                            val index = min(ship.augments.size, i)
                            ship.augments.add(index, it)
                        }
                    }
                )
            }

            // Check the cargo bay
            for (button in cargoButtons) {
                if (button != target)
                    continue
                val i = cargoSlots.getValue(button)

                return SlotAccess(
                    { slotGet(i) },
                    { slotSet(i, it) }
                )
            }

            // Check the sell button
            if (target == sellButton) {
                return SlotAccess({ null }) {
                    // Give the player scrap for the item
                    if (sellUI) ship.scrap += (it?.cost ?: 0) / 2

                    // Do nothing with it, thus destroying it.

                    sellSound.play()
                }
            }

            return null
        }

        // Find the blueprint the user is trying to drop the item into
        val hovered = target ?: buttons.firstOrNull { it.hovered } ?: return
        val weaponFrom = weaponButtons.indexOf(drag)
        val weaponTo = weaponButtons.indexOf(hovered)
        if (weaponFrom >= 0 && weaponTo >= 0) {
            ship.weapons?.swapSlots(weaponFrom, weaponTo)
            updateButtons()
            return
        }

        // Get access to where this blueprint is being dragged to and from
        val src = getAccess(drag) ?: return
        val dst = getAccess(hovered) ?: return

        // Make sure something crazy hasn't happened
        require(drag.blueprint == src.get())

        // Equipment::MouseUp (0x539d40): the box dragged to is only the sell box in the
        // store (flag +0x234 needs the sell mode +0x3e2). The over-capacity box
        // is a warning with no drop action, so the item stays where it was.
        if (isLeaveBehindBox(hovered))
            return

        // A locked augment's box takes nothing (bBlocked).
        if (hsLocked(dst.get())) return

        // Check if the blueprint will fit - the sell button is the exception
        // here, you can sell anything you can drag.
        if (hovered is Buttons.DragDropBlueprintButton && !hovered.compatible(drag.blueprint!!))
            return

        // The drop table (0x5399f0): an augment dropped on an augment box needs
        // the box to be empty (rule 3); the only way to swap two augments is
        // through the overflow augment box (rules 5 and 6).
        if (drag in augmentButtons && hovered in augmentButtons && dst.get() != null)
            return

        // If the user drops one blueprint onto another, they swap.
        // Make sure that'll work.
        val replacing = dst.get()
        if (replacing != null && !drag.compatible(replacing))
            return

        // Swap them
        dst.set(drag.blueprint)
        src.set(replacing)

        // Equipment::MouseUp hook: the shown over-capacity page takes what its box holds now.
        if (hsUi) xyz.znix.xftl.hs.ship.HsEquipment.syncOver(ship)
        ship.cargoUpdated()
    }

    /**
     * WeaponEquipBox::RemoveItem -> WeaponSystem::RemoveWeapon: the weapon leaves the system's list, so the weapons after
     * it move up a slot (1.6.14 ps-w3-van w3v-equip e-3-to-hold: the third slot's laser to the hold, the fourth slot's
     * Fire Bomb now third and the fourth slot empty).
     */
    private fun removeWeaponClosingGap(slot: Int) {
        val slots = minOf(ship.weaponSlots ?: ship.hardpoints.size, ship.hardpoints.size)
        ship.hardpoints[slot].weapon = null
        for (j in slot until slots - 1) {
            ship.hardpoints[j].weapon = ship.hardpoints[j + 1].weapon
            ship.hardpoints[j + 1].weapon = null
        }
        ship.weapons?.let { it.selectedTargets.reindex(); it.powerManager.powerStateChanged() }
    }

    private fun slotGet(id: Int): Blueprint? = if (id >= 100) ship.cargoAt(id - 100 + 4) else ship.cargoBlueprints[id]
    private fun slotSet(id: Int, item: Blueprint?) {
        if (id >= 100) ship.setCargoAt(id - 100 + 4, item) else ship.cargoBlueprints[id] = item
    }

    private fun overCapacityShown(slot: Int) = ship.cargoBlueprints[slot] != null || ship.overCapacityBoxShown[slot - 4]

    /** A paging arrow (CustomEquipment's augment, cargo and over-capacity buttons): the `_on` image, `_select2` on hover. */
    private inner class ArrowButton(private val base: String, pos: ConstPoint, private val mirrored: Boolean, val overPager: Boolean = false, private val action: () -> Unit) :
        Button(game, pos, ConstPoint(game.getImg("${base}_on.png").width, game.getImg("${base}_on.png").height)) {
        override val makesHoverNoise: Boolean get() = true

        override fun draw(g: Graphics) {
            val image = game.getImg("${base}_${if (hovered) "select2" else "on"}.png")
            if (mirrored) {
                g.pushTransform(); g.translate(pos.x + image.width.f, pos.y.f); g.scale(-1f, 1f); image.draw(0, 0); g.popTransform()
            } else image.draw(pos.x, pos.y)
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_LEFT_BUTTON) action()
        }
    }

    /** The over-capacity box (not the store's sell box): natively it has no drop action. */
    private fun isLeaveBehindBox(target: Button) = target in overCapacityBoxes

    /**
     * An over-capacity DropBox (DropBox ctor 0x4e8750 without the sell flag, DropBox::OnRender 0x4e8b30):
     * dropbox_on art (select2 while an item is dragged over it), cut like the sell box: the first 32 rows,
     * row 32 stretched by the second title line, rows 33-64, row 64 stretched over the body, the rest. Title
     * font 63 centred from y+12 (a two-line title from y+10 and y+38), text1 font 52 centred from y+57 below
     * the title, text2 after text1 and the gap. DropBox::CalcHeight (0x4e8270): body = text1 + gap + text2 - 20.
     */
    private inner class OverCapacityBox(titleKey: String, text1Key: String, private val gap: Int, text2Key: String) :
        Button(game, overBoxPos, ConstPoint(272, overCapacityHeight(titleKey, text1Key, gap, text2Key))) {

        override val makesHoverNoise: Boolean get() = false

        /** The augment box (overAugImage), else the item box (overBox). */
        val augmentBox = titleKey == "overcapacity_aug_title"

        private val title = game.translator[titleKey]
        private val text1 = game.translator[text1Key]
        private val text2 = game.translator[text2Key]
        private val titleExtra = if ('\n' in title) 24 else 0
        private val text1Height = textLines(text1) * 24

        /** DropBox::GetBodySpaceOffset: where the slot goes below the box's top. */
        val bodySpaceOffset: Int get() = text1Height + titleExtra + 65

        override fun draw(g: Graphics) {
            val hoverActive = hovered && draggingBlueprint != null
            val img = game.getImg(if (hoverActive) "img/dropbox_select2.png" else "img/dropbox_on.png")
            val x = pos.x.f
            val y = pos.y.f
            val w = img.width.f
            val te = titleExtra.f
            val body = (text1Height + gap + textLines(text2) * 24 - 20).f

            img.draw(x, y, x + w, y + 32f, 0f, 0f, w, 32f)
            img.draw(x, y + 32f, x + w, y + 33f + te, 0f, 32f, w, 33f)
            img.draw(x, y + 33f + te, x + w, y + 64f + te, 0f, 33f, w, 64f)
            img.draw(x, y + 64f + te, x + w, y + 65f + te + body, 0f, 64f, w, 65f)
            img.draw(x, y + 65f + te + body, x + w, y + img.height + te + body, 0f, 65f, w, img.height.f)

            // Drawn inside Equipment::OnRender's GL_Translate to the window origin (0x0053a9f0), left of
            // which printCenter's half pixels round left.
            val frame = position.x.f
            val titleColour = xyz.znix.xftl.rendering.Colour(28, 21, 21)
            val lines = title.split('\n', limit = 2)
            if (lines.size == 2) {
                overCapacityTitleFont.printCenter(x + w / 2f, y + 10f, lines[0], titleColour, frame)
                overCapacityTitleFont.printCenter(x + w / 2f, y + 38f, lines[1], titleColour, frame)
            } else {
                overCapacityTitleFont.printCenter(x + w / 2f, y + 12f, title, titleColour, frame)
            }
            val textColour = if (hoverActive) xyz.znix.xftl.rendering.Colour(255, 230, 94) else Constants.TEXT_BUTTON_NORMAL
            overCapacityBodyFont.printCenter(x + w / 2f, y + 57f + te, text1, textColour, frame)
            overCapacityBodyFont.printCenter(x + w / 2f, y + 57f + te + text1Height + gap, text2, textColour, frame)
        }

        override fun click(button: Int) {
            // A warning box: nothing happens on a click.
        }
    }

    private val overCapacityTitleFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)
    private val overCapacityBodyFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 52)

    private fun textLines(text: String) = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    private fun overCapacityHeight(titleKey: String, text1Key: String, gap: Int, text2Key: String): Int =
        dropBoxHeight(game.translator, titleKey, text1Key, gap, text2Key)

    companion object {
        /** How far a finger moves on a selected box before the item comes up with it. */
        private const val TOUCH_DRAG_DIST = 9

        private fun lines(text: String) = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

        /**
         * DropBox::GetHeight of a box with these texts (an empty key is no text): DropBox::CalcHeight (0x4e8270), 94 px
         * with a one-line title, 24 more for a second title line, and the body, text1 + gap + text2 in 24 px lines - 20.
         */
        fun dropBoxHeight(translator: xyz.znix.xftl.Translator, titleKey: String, text1Key: String, gap: Int, text2Key: String): Int {
            val titleExtra = if ('\n' in translator[titleKey]) 24 else 0
            val body = lines(translator[text1Key]) * 24 + gap + (if (text2Key.isEmpty()) 0 else lines(translator[text2Key])) * 24 - 20
            return 94 + titleExtra + body
        }
    }

    class SellDropBox private constructor(
        game: InGameState, pos: IPoint,
        private val type: Type,
        private val container: WidgetContainer,
        private val getDrag: () -> Blueprint?,
        private val getHovered: () -> Blueprint?,
    ) :
        Button(game, pos, container.root.size) {

        override val makesHoverNoise: Boolean get() = false

        // Where a dragged item is dropped: the finger is the mouse from its press.
        override val touchLift: Boolean get() = false

        private val images: List<ImageView> = container.allWidgets.filterIsInstance(ImageView::class.java)

        private val highlightingLabels: List<Label> = listOfNotNull(
            container.byId["message"] as Label,
            container.byId["value_label"] as Label?,
            container.byId["price"] as Label?,
        )

        private val price = container.byId["price"] as Label?

        override fun draw(g: Graphics) {
            val normalPath = when (type) {
                Type.SELL_EQUIPMENT -> "img/dropbox_sell_on.png"
                else -> "img/dropbox_on.png"
            }
            val hoverPath = when (type) {
                Type.SELL_EQUIPMENT -> "img/dropbox_sell_select2.png"
                else -> "img/dropbox_select2.png"
            }

            val draggingBlueprint = getDrag()

            val hoverActive = hovered && draggingBlueprint != null
            val image = when {
                hoverActive -> game.getImg(hoverPath)
                else -> game.getImg(normalPath)
            }
            for (view in images) {
                view.image = image
            }

            val colour = when {
                hoverActive -> Constants.UI_BUTTON_HOVER
                else -> Constants.SECTOR_CUTOUT_TEXT
            }
            for (label in highlightingLabels) {
                label.colour = colour
            }

            if (price == null) {
                // For the over-capacity boxes
            } else {
                // Equipment::MouseMove (HS CustomEquipment::MouseMove keeps it): the value is the dragged item's, else
                // the hovered one's, and empty over nothing (1.6.14 ps-w3-van s-h-sell1: 19 for the hovered Artemis).
                val shown = draggingBlueprint ?: getHovered()

                if (shown != null) {
                    // You only get half of what you paid for it
                    val sellPrice = (shown.cost ?: 0) / 2
                    price.text = sellPrice.toString()
                } else {
                    price.text = ""
                }
            }

            if (type == Type.SELL_EQUIPMENT && !PlatformSpecific.INSTANCE.isTouchUi) {
                drawNativeSellBox(hoverActive)
                return
            }

            g.pushTransform()
            g.translate(pos.x.f, pos.y.f)
            container.draw(g)
            g.popTransform()
        }

        private val titleFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)
        private val bodyFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 52)
        private val priceFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 0)

        /**
         * DropBox::OnRender (0x004e8b30): the art's first 32 rows, row 32 stretched for a
         * two-line title, rows 33-64, row 64 stretched across the body, then the rest;
         * title font 63 centred from y+12 (+10 for two lines), body font 52 centred
         * from y+57, SELL VALUE font 52 centred at (102, 85+body), price font 0 at
         * (214, 90+body). The widget sits 7px inside the art's glow.
         */
        private fun drawNativeSellBox(hoverActive: Boolean) {
            val img = game.getImg(if (hoverActive) "img/dropbox_sell_select2.png" else "img/dropbox_sell_on.png")
            val x = pos.x - 7f
            val y = pos.y - 7f
            val w = img.width.f
            val title = (container.byId["title"] as Label).text
            val body = (container.byId["message"] as Label).text
            val valueLabel = (container.byId["value_label"] as Label).text
            val titleExtra = if ('\n' in title) 24f else 0f
            // Measured: four 24px lines give an 83px body (three gaps plus 11).
            val bodyHeight = body.count { it == '\n' } * 24f + 11f

            img.draw(x, y, x + w, y + 32f, 0f, 0f, w, 32f)
            img.draw(x, y + 32f, x + w, y + 33f + titleExtra, 0f, 32f, w, 33f)
            img.draw(x, y + 33f + titleExtra, x + w, y + 64f + titleExtra, 0f, 33f, w, 64f)
            img.draw(x, y + 64f + titleExtra, x + w, y + 65f + titleExtra + bodyHeight, 0f, 64f, w, 65f)
            img.draw(x, y + 65f + titleExtra + bodyHeight, x + w, y + img.height + titleExtra + bodyHeight, 0f, 65f, w, img.height.f)

            val textColour = if (hoverActive) Colour(255, 230, 94) else Constants.TEXT_BUTTON_NORMAL
            titleFont.printCenter(x + w / 2f, y + if (titleExtra > 0f) 10f else 12f, title, Colour(28, 21, 21))
            bodyFont.printCenter(x + w / 2f, y + 57f + titleExtra, body, textColour)
            bodyFont.printCenter(x + 102f, y + 85f + bodyHeight, valueLabel, textColour)
            price?.text?.takeIf { it.isNotEmpty() }?.let {
                priceFont.print(x + 214f, y + 90f + bodyHeight, it, textColour)
            }
        }

        override fun click(button: Int) {
            // Clicking the sell box doesn't do anything
        }

        companion object {
            // Touch-only: side margin between the message text and the
            // sell box's frame art (total width increase is twice this).
            private const val SELL_TEXT_MARGIN = 16

            // Left edge of the scrap icon baked into the value row of
            // img/dropbox_sell_on.png (art x 186 of 272, measured from
            // the vanilla sheet). Only used to re-anchor the number on
            // widened touch boxes, see create().
            private const val SELL_ICON_ART_LEFT = 186

            // Gap between the number's right edge and the icon (by-eye).
            private const val SELL_NUMBER_ICON_GAP = 6

            fun create(
                game: InGameState, type: Type, pos: IPoint,
                getDrag: () -> Blueprint?, getHovered: () -> Blueprint?
            ): SellDropBox {
                val widgetName = when (type) {
                    Type.SELL_EQUIPMENT -> "sell_drop_box"
                    Type.TOO_MUCH_EQUIPMENT -> "crew_over_capacity"
                    Type.TOO_MANY_CREW -> "crew_over_capacity"
                }

                val widget: WidgetContainer = game.uiLoader.load(widgetName).mainWidget

                val titleKey = when (type) {
                    Type.SELL_EQUIPMENT -> "sell_box_title"
                    Type.TOO_MUCH_EQUIPMENT -> "overcapacity_item_title"
                    Type.TOO_MANY_CREW -> "overcapacity_crew_title"
                }
                val messageKey = when (type) {
                    Type.SELL_EQUIPMENT -> "sell_box_text"
                    Type.TOO_MUCH_EQUIPMENT -> "overcapacity_item_text1"
                    Type.TOO_MANY_CREW -> "overcapacity_crew_text"
                }

                (widget.byId["title"] as Label).text = game.translator[titleKey]
                val message = widget.byId["message"] as Label
                message.text = game.translator[messageKey]

                // Touch layouts have no vertical room to spare (user):
                // collapse the vanilla 4-line message to 3 by merging its
                // last two lines. The box auto-widens horizontally to fit
                // the longer line, trading width for height. Generic -
                // works for any language's line breaks.
                if (PlatformSpecific.INSTANCE.isTouchUi) {
                    val t = message.text
                    val lastBreak = t.lastIndexOf('\n')
                    if (lastBreak >= 0)
                        message.text = t.substring(0, lastBreak) + " " + t.substring(lastBreak + 1)

                    // The vanilla box hugs its widest line (the frame art
                    // has no built-in padding), so the text sits flush
                    // against the frame. Stretch the label - the widest
                    // child, hence the root width driver - so the frame
                    // extends past the text and every centred line gets
                    // side margins. Sell box only: the crew over-capacity
                    // box tucks against ShipWindow's crew grid and must
                    // stay narrow.
                    if (type == Type.SELL_EQUIPMENT)
                        message.attemptStretch(message.size.x + 2 * SELL_TEXT_MARGIN, 0)
                }

                widget.updateLayout()

                // On a widened (touch) box the art band with the baked-in
                // scrap icon stretches too: the icon slides right and ends
                // up under the value number, whose fixed pLeft assumes the
                // art's natural 272px width (pocket-wormhole issue 39).
                // Wide boxes: re-anchor the number to sit just LEFT of the
                // icon's drawn position. At natural width (scale 1) this
                // computes to the XML pLeft and nothing moves.
                if (type == Type.SELL_EQUIPMENT && PlatformSpecific.INSTANCE.isTouchUi) {
                    // The bottom (value row) band - the last image in
                    // sell_drop_box.xml; every band shares the box's width.
                    val band = widget.allWidgets.filterIsInstance(ImageView::class.java).last()
                    val scale = band.size.x.f / band.baseSize.x.f
                    if (scale > 1.001f) {
                        val price = widget.byId["price"] as Label
                        val iconLeft = band.position.x + SELL_ICON_ART_LEFT * scale
                        val desired = iconLeft - SELL_NUMBER_ICON_GAP - price.size.x
                        val shift = (desired - price.position.x).roundToInt()

                        // Move the live position - updateLayout() never runs
                        // again after create(), so only touching parentLeft
                        // (for any future re-layout) changes nothing.
                        price.position.x += shift
                        price.parentLeft = price.parentLeft?.plus(shift)
                    }
                }

                return SellDropBox(game, pos, type, widget, getDrag, getHovered)
            }
        }

        enum class Type {
            SELL_EQUIPMENT,
            TOO_MUCH_EQUIPMENT,
            TOO_MANY_CREW,
        }
    }
}
