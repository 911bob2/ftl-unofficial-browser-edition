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

        if (sellUI && ship.cargoBlueprints.drop(4).any { it != null }) sectionFont.drawString(position.x - 250f, position.y + 280f, "OVER CAPACITY", Constants.JUMP_DISABLED_TEXT)
        for (button in buttons) {
            button.draw(g)
        }
    }


    /** EquipmentBox for an augment (box_augment art, name in font 13); also the over-capacity augment box. */
    private fun augmentButton(buttonPos: IPoint, augment: Blueprint?): Buttons.DragDropBlueprintButton {
        val size = ConstPoint(235, 40)
        // Use a separate variable so we can use the button in its callback.
        lateinit var button: Buttons.DragDropBlueprintButton
        button = object : Buttons.DragDropBlueprintButton(
            buttonPos, game, null, size,
            { it is AugmentBlueprint },
            augment,
            { draggingBlueprint = button }
        ) {
            override fun draw(g: Graphics) {
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

    fun drawDrag(g: Graphics) {
        // Draw the dragged bit of equipment above everything else
        draggingBlueprint?.drawDrag(g)
    }

    fun shipModified() {
        updateButtons()
    }

    private fun updateButtons() {
        buttons.clear()

        // Draw the weapons
        // Note: I think all the playable ships have weaponSlots set?
        weaponButtons.clear()
        for (i in 0 until ship.weaponSlots!!) {
            val weapon = ship.hardpoints[i].weapon

            val images = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_weapons", true)
            val buttonPos = ConstPoint(slotX(ship.weaponSlots!!, i), 70)

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

        // Draw the augments area
        augmentButtons.clear()
        for (i in 0 until 3) {
            val button = augmentButton(ConstPoint(323, 293 + i * 60), ship.augments.getOrNull(i))
            buttons += button
            augmentButtons += button
        }

        // Equipment::SetPosition (0x539430): outside the store each overflow slot has its own over-capacity
        // DropBox at (-275, 100) (7 px left in this panel's frame, like every box), drawn (Equipment::OnRender 0x53a9f0) before the slots and only while that
        // slot is in use: the item box (item_text1, 100 px, item_text2) with the cargo box 80 px in, and the
        // augment box (aug_text1, 65 px, aug_text2) with an augment box 19 px in and 4 px lower, both at the
        // DropBox body offset (DropBox::GetBodySpaceOffset 0x4e80c0: title and text1 + 65). A box stays, emptied,
        // until the next jump once something was put in it (Equipment +0x3e0/+0x3e1, Ship.overCapacityBoxShown).
        cargoButtons.clear()
        cargoSlots.clear()
        overCapacityBoxes.clear()
        if (!sellUI) {
            if (overCapacityShown(4))
                overCapacityBoxes += OverCapacityBox("overcapacity_item_title", "overcapacity_item_text1", 100, "overcapacity_item_text2")
            if (overCapacityShown(5))
                overCapacityBoxes += OverCapacityBox("overcapacity_aug_title", "overcapacity_aug_text1", 65, "overcapacity_aug_text2")
            buttons += overCapacityBoxes
        }

        // Draw the cargo area
        for (i in 0 until 6) {
            if (i >= 4 && (if (sellUI) ship.cargoBlueprints.drop(4).all { it == null } else !overCapacityShown(i))) continue
            val blueprint = ship.cargoBlueprints[i]

            if (i == 5 && !sellUI) {
                val box = overCapacityBoxes.last()
                val augmentBox = augmentButton(ConstPoint(box.basePos.x + 19, box.basePos.y + box.bodySpaceOffset + 4), blueprint)
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
            val buttonPos = if (i == 4 && !sellUI && itemBox != null)
                ConstPoint(itemBox.basePos.x + 80, itemBox.basePos.y + itemBox.bodySpaceOffset)
            else if (i >= 4) ConstPoint(-250, 293 + 80 * (i - 4)) else ConstPoint(
                23 + 130 * (i % 2),
                293 + 80 * (i / 2)
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

            sellBoxLocalPos = sellBoxPlacer?.invoke(sellButton!!.size) ?: ConstPoint(-275, 107)

            buttons += sellButton!!
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
        // Copied from Window.
        // Mouse clicking may change the buttons array (eg in the store
        // window when switching tabs), so copy it.
        for (btn in ArrayList(buttons)) {
            btn.mouseDown(button, x, y)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun mouseReleased(button: Int, x: Int, y: Int) {
        if (draggingBlueprint != null)
            dropBlueprint()
    }

    fun getHoveredBlueprint(): Blueprint? {
        // Don't show anything while a blueprint is being dragged.
        if (draggingBlueprint != null)
            return null

        val hoveredButton = buttons.firstOrNull { it.hovered }
        if (hoveredButton !is Buttons.DragDropBlueprintButton)
            return null

        return hoveredButton.blueprint
    }

    fun drawInfoPanel(infoPanel: InfoPanel) {
        val blueprint = getHoveredBlueprint() ?: return
        return when (blueprint) {
            is DroneBlueprint -> infoPanel.drawDrone(blueprint)
            is AugmentBlueprint -> infoPanel.drawAugment(blueprint)
            is AbstractWeaponBlueprint -> infoPanel.drawWeapon(blueprint)
            else -> error("Cannot draw info panel for $blueprint")
        }
    }

    private fun dropBlueprint() {
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
                    { ship.hardpoints[i].weapon = (it as? AbstractWeaponBlueprint)?.buildInstance(ship) }
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
            for ((i, button) in augmentButtons.withIndex()) {
                if (button != target)
                    continue

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
                    { ship.cargoBlueprints[i] },
                    { ship.cargoBlueprints[i] = it }
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
        val hovered = buttons.firstOrNull { it.hovered } ?: return
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

        ship.cargoUpdated()
    }

    private fun overCapacityShown(slot: Int) = ship.cargoBlueprints[slot] != null || ship.overCapacityBoxShown[slot - 4]

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
        Button(game, ConstPoint(-275 - 7, 100), ConstPoint(272, overCapacityHeight(titleKey, text1Key, gap, text2Key))) {

        override val makesHoverNoise: Boolean get() = false

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

    private fun overCapacityHeight(titleKey: String, text1Key: String, gap: Int, text2Key: String): Int {
        val titleExtra = if ('\n' in game.translator[titleKey]) 24 else 0
        val body = textLines(game.translator[text1Key]) * 24 + gap + textLines(game.translator[text2Key]) * 24 - 20
        return 94 + titleExtra + body
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
                // The vanilla box shows the value only while an item is
                // being dragged. Touch has no sustained drag - items are
                // picked up and dropped with taps (Window.tapToArm) - so
                // there the value would flash for a single frame at most
                // and was never visible (pocket-wormhole issue 39). On
                // touch, fall back to previewing the value of the hovered
                // item so it stays visible before committing to the sale.
                val shown = draggingBlueprint
                    ?: if (PlatformSpecific.INSTANCE.isTouchUi) getHovered() else null

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
