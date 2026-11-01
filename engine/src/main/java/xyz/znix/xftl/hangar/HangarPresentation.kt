package xyz.znix.xftl.hangar

import xyz.znix.xftl.*
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.CrewNameManager
import xyz.znix.xftl.game.StartingCrew
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.rendering.*
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.random.Random

/** Desktop hangar UI. Preview data never creates a run or alters a blueprint. */
class HangarPresentation(private val state: SelectShipState) {
    private val heading = state.getFont("HL2").apply { scale = 2f }
    private val body = state.getFont("JustinFont12Bold")
    private val small = state.getFont("c&c")
    private val tooltipFont = state.getFont("JustinFont11Bold")
    private val tooltipTitleFont = state.getFont("JustinFont12Bold")
    private val nativeFonts = HashMap<Int, NativeFonts.NativeFont>()
    private fun font(id: Int) = nativeFonts.getOrPut(id) { NativeFonts.font(state::getFont, id) }
    private val touch get() = HangarLayout.touch
    private fun pick(desktop: Int, touch: Int) = HangarLayout.pick(desktop, touch)

    /** Ship-select panel geometry from ShipBuilder::OnInit (0x004f1dd0): sized from the labels. */
    private class ShipBox(val listX: Int, val listWidth: Int, val randomX: Int, val randomWidth: Int,
                          val roomsWidth: Int, val extra: Int)

    private val shipBox by lazy {
        val f = font(BUTTON_FONT)
        val list = f.width(state.translator["ship_list_button"])
        val random = f.width(state.translator["ship_random_button"]) + 8
        val rooms = maxOf(f.width(state.translator["ship_hiderooms_button"]), f.width(state.translator["ship_showrooms_button"]))
        val image = state.getImg("img/customizeUI/box_shipselect.png").width
        val width = maxOf(random - 8 + 40, list + 122, rooms + 8 + 40, image)
        val centre = width / 2
        ShipBox(centre - (list + 8) / 2, list + 8, centre - random / 2, random, width - 40, width - image)
    }

    /** Advanced Edition panel: auto-width buttons, then the box is sliced around them (0x004f1dd0). */
    private class AdvancedBox(val offWidth: Int, val onWidth: Int, val start: Int)

    private val advancedBox by lazy {
        val f = font(BUTTON_FONT)
        var off = f.width(state.translator["advanced_off_button"]) + 6
        var on = f.width(state.translator["advanced_on_button"]) + 6
        val title = font(13).width(state.translator["hangar_advanced_title"])
        val grow = maxOf(0, title + 34 - (off + on + 55))
        off += grow shr 1
        on += grow - grow / 2
        AdvancedBox(off, on, 267 - (off + on + 55))
    }

    private val achievementPadding by lazy {
        maxOf(0, (font(13).width(state.translator["hangar_achievements_title"]) - 208) / 10) * 10
    }
    private val random = Random(System.nanoTime().toInt())

    /** Hyperspace's additions to this screen (CustomShipSelect.cpp); null in vanilla. */
    private val hs = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsHangarUi(state, ::font) else null
    private val names = CrewNameManager(state.datafile, "en")
    private val infoPanel = xyz.znix.xftl.game.InfoPanel(state)
    var shipName = ""
        private set
    val crew = ArrayList<StartingCrew>()
    private var editing = -2 // -2 none, -1 ship, otherwise starting crew index
    private var text = ""
    private var cursor = 0
    private var customizing = -1
    private var elapsed = 0f
    // TextInput's blink TimerHelper: 1s loop, reset whenever the cursor moves.
    private var blink = 0f
    private var blinkCursor = -1
    private var hovered: String? = null
    val isEditing get() = editing != -2
    val isCustomizing get() = customizing >= 0
    val diagnosticEditing get() = editing
    val diagnosticName get() = if (editing == -1) text else shipName
    val diagnosticTooltip get() = if(isEditing || isCustomizing || state.isShipListVisible) null else controls().firstOrNull { it.id==hovered }?.hint ?: shipHint() ?: crewHint() ?: equipmentHint() ?: achievementHint() ?: systemHint() ?: hs?.reactorInfo()?.second ?: state.hoveredDifficulty?.let { state.translator[it.name.lowercase()+"_text"] }
    val diagnosticControls get() = controls().joinToString(",", "[", "]") {
        "{\"id\":${ftl.browser.BrowserDebug.quote(it.id)},\"x\":${it.x},\"y\":${it.y},\"width\":${it.w},\"height\":${it.h},\"enabled\":${it.enabled},\"selected\":${it.selected}}"
    }

    fun drawBackgroundAnimations() {
        fun draw(name: String,x: Int,y: Int) {
            val anim=state.animations[name]
            val frame=(elapsed/anim.time).toInt()%anim.length
            anim.sheet.getSprite(state.getImg(anim.sheet.sheetPath),anim.x+frame,anim.y).draw(x,y)
        }
        // The iPad art is the desktop's 12 px to the right, and ends before the desktop's other animations.
        if(state.hasTouchBackground) {
            draw("custom_type",99,96)
            return
        }
        draw("custom_type",87,96)
        draw("custom_repair",1090,100)
        draw("custom_repair",1077,342)
        draw("custom_rock",1245,136)
    }

    fun drawThrusters() {
        // Native graphic initialization enables these jets only on these hulls.
        val federation=state.blueprint.layout in listOf("fed_cruiser","fed_cruiser_2","fed_cruiser_3")
        if(!federation && state.blueprint.layout !in listOf("kestral","kestral_2","kestral_3")) return
        val frame=(elapsed*8).toInt()%4
        val image=state.getImg("img/effects/thrusters_on.png").getSubImage(frame*22,0,22,70)
        val offset=state.blueprint.hullOffset
        image.draw(offset.x+if(federation) 17 else 60,offset.y+if(federation) 44 else 39)
        image.draw(offset.x+if(federation) 17 else 60,offset.y+if(federation) 285 else 305)
    }

    data class Control(val id: String, val x: Int, val y: Int, val w: Int, val h: Int,
                       val label: String = "", val enabled: Boolean = true, val selected: Boolean = false,
                       val art: String? = null, val hint: String? = null, val action: () -> Unit) {
        fun contains(px: Int, py: Int) = px in x until x+w && py in y until y+h
    }

    fun select() {
        editing = -2
        xyz.znix.xftl.game.TouchMode.keyboardWanted = false
        customizing = -1
        shipName = state.blueprint.shipTitle?.let { state.translator[it] } ?: state.selectedShipId
        crew.clear()
        for (spec in state.blueprint.initialCrew) repeat(spec.amount) {
            // Hyperspace: crew lists and unknown races resolve as GetCrewBlueprint does (unknown -> human).
            val race = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrewContent.crewBlueprint(state.blueprints, spec.race, random)
            else state.blueprints.crewOrHuman(spec.race)
            val species = race.name
            val colour = if (race.numberOfColours == 0) 0 else random.nextInt(race.numberOfColours)
            crew += StartingCrew(species, names.getForGender(if (species == "human") colour < race.baseNumberOfColours else null, random), colour)
        }
    }

    fun restore(name: String, choices: List<StartingCrew>, visible: Boolean) {
        if (choices.map { it.species } == crew.map { it.species }) {
            shipName = name
            crew.clear(); crew.addAll(choices.map { it.copy() })
        }
        state.setRoomsVisible(visible)
    }

    fun controls(): List<Control> {
        val result = ArrayList<Control>()
        fun add(id: String, x: Int, y: Int, w: Int, h: Int, label: String = "", enabled: Boolean = true,
                selected: Boolean = false, art: String? = null, hint: String? = null, action: () -> Unit) {
            result += Control(id,x,y,w,h,label,enabled,selected,art,hint,action)
        }
        if (isCustomizing) {
            val c = crew[customizing]
            val race = state.blueprints[c.species] as CrewBlueprint
            add("crewPrevious",111,559,32,28,enabled=race.numberOfColours>1,art="button_arrow") {
                c.colour=(c.colour+race.numberOfColours-1)%race.numberOfColours
            }
            add("crewNext",263,559,32,28,enabled=race.numberOfColours>1,art="button_arrow") {
                c.colour=(c.colour+1)%race.numberOfColours
            }
            add("crewRename",105,635,94,24,state.translator["crewbox_rename"]) { edit(customizing) }
            add("crewAccept",206,635,94,24,state.translator["crewbox_accept"]) { commit();customizing=-1 }
        }
        val box = shipBox
        if (touch) {
            val listWidth = font(BUTTON_FONT).width(state.translator["ship_list_button"]) + 8
            val listX = (TOUCH_SHIP_BOX_WIDTH - listWidth) / 2
            val randomWidth = font(BUTTON_FONT).width(state.translator["ship_random_button"]) + 8
            add("previous",listX-58,159,47,41,enabled=state.canCycleShips(-1),art="button_arrow") { state.selectNextShip(-1) }
            add("list",listX,159,listWidth,41,state.translator["ship_list_button"]) { state.openShipList() }
            add("next",listX+listWidth+17,159,47,41,enabled=state.canCycleShips(1),art="button_arrow") { state.selectNextShip(1) }
            add("random",(TOUCH_SHIP_BOX_WIDTH-randomWidth)/2,210,randomWidth,32,state.translator["ship_random_button"],state.unlockedFamilyCount>1) { state.selectRandomShip(random) }
            add("mainMenu",815,15,195,36,state.translator["hangar_exit_button"]) { commit();state.back() }
        } else {
        add("previous",box.listX-37,145,32,28,enabled=state.canCycleShips(-1),art="button_arrow") { state.selectNextShip(-1) }
        add("list",box.listX,145,box.listWidth,28,state.translator["ship_list_button"]) { state.openShipList() }
        add("next",box.listX+box.listWidth+5,145,32,28,enabled=state.canCycleShips(1),art="button_arrow") { state.selectNextShip(1) }
        add("random",box.randomX,178,box.randomWidth,32,state.translator["ship_random_button"],state.unlockedFamilyCount>1) { state.selectRandomShip(random) }
        }
        val family = state.shipFamilies.byShipId[state.selectedShipId]
        // ShipBuilder::CheckTypes: the two-layout Crystal and Lanius families move
        // A and B 22px right and park C off screen.
        val twoLayouts = family != null && family.ships.size < 3
        for (i in 0 until if (twoLayouts) 2 else 3) {
            val id = family?.ships?.getOrNull(i)
            val exists = id != null && state.ships.any { it.name == id }
            val unlocked = exists && state.isShipUnlocked(id!!)
            // ShipBuilder::MouseMove sets no tooltip for the layout buttons, locked or not.
            if (touch) add("layout$i",27+i*66+(if (twoLayouts) 33 else 0),280,46,44,state.translator["typebutton_"+('a'+i)],unlocked,id==state.selectedShipId) { state.selectShipVariant(i) }
            else add("layout$i",32+i*45+(if (twoLayouts) 22 else 0),248,36,34,"${'A'+i}",unlocked,id==state.selectedShipId) { state.selectShipVariant(i) }
        }
        val roomsLabel = state.translator[if (state.roomsVisible) "ship_hiderooms_button" else "ship_showrooms_button"]
        if (touch) {
            val roomsWidth = maxOf(149, font(BUTTON_FONT).width(state.translator["ship_hiderooms_button"]) + 8, font(BUTTON_FONT).width(state.translator["ship_showrooms_button"]) + 8)
            add("rooms",(TOUCH_SHIP_BOX_WIDTH-roomsWidth)/2,340,roomsWidth,28,roomsLabel) { state.setRoomsVisible(!state.roomsVisible) }
        } else add("rooms",20,301,box.roomsWidth,28,roomsLabel) { state.setRoomsVisible(!state.roomsVisible) }
        add("rename",pick(14,15),16,94,32,state.translator["ship_rename_button"]) { edit(-1) }
        // Native button locations are set while slicing the panel: 24px cap, then each button.
        val ae = advancedBox
        val aeLeft = advancedX + ae.start
        val aeTop = pick(425,399)
        // Hyperspace <forceDlc> moves the AE buttons off screen (CustomShipSelect.cpp ShipBuilder::OnRender).
        if (!xyz.znix.xftl.hs.HS.forceDlc()) {
        add("aeOff",aeLeft+24-9,aeTop,ae.offWidth,22,state.translator["advanced_off_button"],selected=!state.advancedEdition,
            hint=state.translator["advanced_off_text"]) { state.changeMode(false) }
        add("aeOn",aeLeft+24+(ae.offWidth-18)+43-9,aeTop,ae.onWidth,22,state.translator["advanced_on_button"],selected=state.advancedEdition,
            hint=state.translator["advanced_on_text"]) { state.changeMode(true) }
        }
        if(!isCustomizing) {
            // The whole touch card opens the details; when rows overlap the later card lies on top.
            if (touch) for (i in crew.indices.reversed()) add("crew$i",crewX(i),crewY(i),100,93,state.translator["crewbox_customize"],art="box_crewcustom") { customizing=i }
            else crew.forEachIndexed { i, c ->
            val x=70+(i%2)*160; val y=519+(i/2)*93
            // Native Customize paints a smaller inner button but overrides its
            // hit rectangle to the full100x20 card strip (00544180).
            add("crew$i",x,y+66,100,20,state.translator["crewbox_customize"],art="box_crewcustom") { customizing=i }
            }
        }
        return result
    }

    /** The crew cards' places: two columns, on touch with the rows closing up so that every card of a large crew fits. */
    private fun crewX(i: Int) = if (touch) 20+(i%2)*112 else 70+(i%2)*160
    private fun crewY(i: Int): Int {
        if (!touch) return 519+(i/2)*93
        val rows = (maxOf(4,crew.size)+1)/2
        return 564+(i/2)*minOf(93,186/rows)
    }
    private val advancedX get() = pick(975,750)
    private val infoAnchor get() = xyz.znix.xftl.math.ConstPoint(pick(952,308),pick(104,79))

    private fun crewDescription(c: StartingCrew): String {
        val race=state.blueprints[c.species] as CrewBlueprint
        return (listOf(race.title?.let { state.translator[it] } ?: c.species)+race.powerStringIds.map { state.translator[it] }).joinToString("\n")
    }

    fun update(delta: Float) {
        elapsed += delta
        if (cursor != blinkCursor) { blinkCursor = cursor; blink = 0f }
        blink += delta
        if (blink > 1f) blink = 0f
        // ShipBuilder::MouseMove does not run under the open ship list, so the
        // LIST button keeps the hover it had when clicked.
        if (!state.isShipListVisible) refreshHover()
    }

    /** ShipBuilder::MouseMove; also run before a click, as native queues the move first. */
    private fun refreshHover() {
        val controls = controls().filter { !isCustomizing || it.id.startsWith("crew") }
        val next=if (isEditing) null else controls.firstOrNull { it.contains(state.mousePos.x,state.mousePos.y) }?.id ?: (if(shipHint()!=null) "description" else null) ?: crewSlot()?.let { "portrait"+it } ?: equipmentSlot() ?: achievementSlot() ?: systemSlot()
        if (next!=hovered) {
            if (controls.firstOrNull { it.id==next }?.let { it.enabled && !it.selected } == true) state.sounds.getSample("hoverBeep").play()
            hovered=next
        }
    }

    fun click(button: Int,x: Int,y: Int): Boolean {
        if (button!=Input.MOUSE_LEFT_BUTTON) return false
        refreshHover()
        if (hs != null && !isEditing && !isCustomizing && hs.click(x,y)) return true
        if (isEditing) commit()
        val control=controls().firstOrNull { (!isCustomizing || it.id.startsWith("crew")) && it.contains(x,y) } ?: return isCustomizing
        if (control.enabled && !control.selected) control.action()
        return true
    }

    private fun edit(index: Int) {
        editing=index; text=if (index==-1) shipName else crew[index].name
        xyz.znix.xftl.game.TouchMode.keyboardWanted = true
        xyz.znix.xftl.game.TouchMode.keyboardFocusY = if (index == -1) 0 else 690
        cursor=text.length
        blinkCursor=-1
    }

    fun commit() {
        if (!isEditing) return
        val name=if(text.isEmpty()) state.translator["nameless_one"] else text
        if (editing==-1) shipName=name else crew[editing].name=name
        editing=-2
        xyz.znix.xftl.game.TouchMode.keyboardWanted = false
    }

    fun key(key: Int,c: Char): Boolean {
        if (!isEditing) {
            // The native hangar handles Escape even while Customize is open.
            if(key==Input.KEY_ESCAPE) return false
            return isCustomizing
        }
        when(key) {
            Input.KEY_ESCAPE, Input.KEY_ENTER -> commit()
            Input.KEY_LEFT -> cursor=(cursor-1).coerceAtLeast(0)
            Input.KEY_RIGHT -> cursor=(cursor+1).coerceAtMost(text.length)
            Input.KEY_HOME -> cursor=0
            Input.KEY_END -> cursor=text.length
            Input.KEY_BACK -> {
                if(cursor>0) { text=text.removeRange(cursor-1,cursor);cursor-- }
            }
            Input.KEY_DELETE -> {
                if(cursor<text.length) text=text.removeRange(cursor,cursor+1)
            }
            // TextInput::OnTextInput: only the characters of the current language's set, up to
            // maxChars: 16 for the ship name, 15 for a crew name (1.6.9 the same).
            else -> if(c>=' ' && c!='\u007f' && xyz.znix.xftl.TextInputChars.accepts(c)) {
                if(text.length<(if(editing==-1) 16 else 15)) { text=text.substring(0,cursor)+c+text.substring(cursor);cursor++ }
            }
        }
        return true
    }

    fun draw(g: Graphics) {
        // ShipBuilder::OnRender panel order: equipment, ship select, achievements, AE.
        if (touch) {
            drawTouchPanel("box_shipequip",0,526,1024,242)
            drawTouchPanel("box_shipselect",0,118,TOUCH_SHIP_BOX_WIDTH,269)
        } else {
            state.getImg("img/customizeUI/box_shipequip.png").draw(0,478)
            drawShipSelectBox()
        }
        atAchievements(g) { drawAchievementBox() }
        if (!xyz.znix.xftl.hs.HS.forceDlc()) drawAdvancedBox()
        if (touch) state.getImgOrNull("img/ipad/customizeUI/box_mainmenu.png")?.let { xyz.znix.xftl.game.TouchArt.frame(it,808,8,209,50,13) }
        state.getImg("img/customizeUI/box_shipname.png").draw(pick(6,7),8)
        heading.drawStringFromTop(pick(19,20).f,pick(117,129).f,state.translator["hangar_frame_ship"],Constants.JUMP_DISABLED_TEXT)
        heading.drawStringFromTop(pick(19,20).f,pick(219,253).f,state.translator["hangar_frame_layout"],Constants.JUMP_DISABLED_TEXT)
        heading.drawStringFromTop(pick(19,20).f,pick(489,535).f,state.translator["hangar_frame_crew"],Constants.JUMP_DISABLED_TEXT)
        heading.drawStringFromTop(pick(388,253).f,pick(489,535).f,state.translator["equipment_frame_weapons"],Constants.JUMP_DISABLED_TEXT)
        heading.drawStringFromTop(pick(388,253).f,pick(599,645).f,state.translator["equipment_frame_drones"],Constants.JUMP_DISABLED_TEXT)
        heading.drawStringFromTop(pick(954,733).f,pick(489,535).f,state.translator["equipment_frame_augments"],Constants.JUMP_DISABLED_TEXT)
        // ShipBuilder::OnRender prints both titles with font 13 in the idle button colour.
        if (hs == null) font(13).printCenter((achievementPadding/2+124+HangarLayout.achievementDx).f,(374+HangarLayout.achievementDy).f,state.translator["hangar_achievements_title"],Constants.TEXT_BUTTON_NORMAL)
        if (!xyz.znix.xftl.hs.HS.forceDlc()) font(13).printCenter((advancedBox.start/2+advancedX+134).f,pick(400,374).f,state.translator["hangar_advanced_title"],Constants.TEXT_BUTTON_NORMAL)
        // TextInput::OnRender(font 24, (273,7)): centred on 273 (ShipBuilder::OnRender).
        drawNativeName(g,if(editing==-1) text else shipName,pick(273,274),7,editing==-1,font(24))
        controls().forEach { control ->
            if(control.id.startsWith("crew"))return@forEach
            // Button::bHover from the last ShipBuilder::MouseMove (frozen under the ship list).
            val over=!isCustomizing && control.id==hovered
            val bgColour = when {
                !control.enabled -> xyz.znix.xftl.hs.render.HsColors.tbInactive()
                control.selected -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
                over -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
                else -> xyz.znix.xftl.hs.render.HsColors.tbActive()
            }
            if(control.art!=null) {
                val suffix=if(!control.enabled) "off" else if(over) "select2" else "on"
                val img=(if(touch) state.getImgOrNull("img/ipad/customizeUI/${control.art}_$suffix.png") else null) ?: state.getImg("img/customizeUI/${control.art}_$suffix.png")
                // The right arrow is the same Button art with bMirror set.
                if(control.id=="next" || control.id=="crewNext") {g.pushTransform();g.translate((control.x+img.width).toFloat(),control.y.toFloat());g.scale(-1f,1f);img.draw(0,0);g.popTransform()} else img.draw(control.x,control.y)
            } else {
                // Native TextButton insets and fonts set by ShipBuilder::OnInit.
                val layout = control.id.startsWith("layout")
                val inset = when {
                    layout || control.id == "rename" -> 6
                    control.id == "aeOff" || control.id == "aeOn" -> 3
                    else -> 4
                }
                // The touch layout buttons sit in a frame whose hole is stretched around them.
                if (touch && layout) state.getImgOrNull("img/ipad/customizeUI/button_type_box.png")?.let { xyz.znix.xftl.game.TouchArt.frame(it,control.x-13,control.y-13,control.w+26,control.h+26,13) }
                drawNativeTextButton(g, control, inset, if (layout || control.id == "mainMenu") 63 else BUTTON_FONT, bgColour)
            }
        }
        if(!isCustomizing) drawCrew(g)
        drawSystems(g)
        drawEquipment(g)
        if (hs != null) {
            atAchievements(g) { hs.drawAchievements(g, achievementSlot()) }
            hs.drawAugmentPager(g)
            hs.drawExtras(g)
        } else atAchievements(g) { drawAchievements(g) }
        // The open box lies over the weapon slots next to it.
        if(isCustomizing) drawCrew(g)
        if(!state.isSelectedUnlocked) {
            g.colour=Colour(0,0,0,180);g.fillRect(pick(300,240),pick(365,330),pick(630,500),86)
            drawTooltip(g,state.lockReason,pick(310,250),pick(375,340),pick(600,470))
        }
        diagnosticTooltip?.let { hint ->
            val item=equipmentItem()
            if(item!=null) {
                infoPanel.position=infoAnchor
                when(item) {
                    is AbstractWeaponBlueprint -> infoPanel.drawWeapon(item)
                    is DroneBlueprint -> infoPanel.drawDrone(item)
                    is xyz.znix.xftl.augments.AugmentBlueprint -> infoPanel.drawAugment(item)
                }
            } else {
                val system=hoveredSystem()
                if(system!=null) {
                    // InfoBox::SetSystem keeps the description frame at the shared (952,104).
                    infoPanel.position=infoAnchor
                    infoPanel.drawDescriptionBoxSystem(system.getBP(state))
                    infoPanel.drawSystemPowerBox(g,system.getBP(state),system.startingPower,0)
                } else {
                    val crewIndex=crewSlot()
                    val ae=hovered=="aeOn" || hovered=="aeOff"
                    val reactor=hs?.reactorInfo()
                    when {
                        reactor!=null -> { infoPanel.position=infoAnchor;infoPanel.drawAutoBox(reactor.first,reactor.second) }
                        crewIndex!=null -> {
                            val c=crew[crewIndex];val race=state.blueprints[c.species] as CrewBlueprint
                            val title=c.name+" ("+(race.title?.let { state.translator[it] } ?: c.species)+")"
                            val powers=race.powerStringIds.map { "-"+state.translator[it] }
                            infoPanel.position=infoAnchor
                            infoPanel.drawDescriptionBox(GameText.literal(title),race.desc,null,powers,xyz.znix.xftl.game.InfoPanel.INFO_HEIGHT_CREW)
                        }
                        // ShipBuilder::MouseMove fills the shared InfoBox (SetDescription, default size).
                        ae -> drawInfoBox(state.translator[if(hovered=="aeOn") "advanced_on_title" else "advanced_off_title"],hint)
                        hovered=="description" -> drawInfoBox(state.blueprint.shipClass?.let { state.translator[it] } ?: state.selectedShipId,state.blueprint.desc?.let { state.translator[it] } ?: "")
                        state.hoveredDifficulty!=null -> drawInfoBox(state.translator[state.hoveredDifficulty!!.name.lowercase()+"_title"],hint)
                        else -> drawTooltip(g,hint,state.mousePos.x,state.mousePos.y)
                    }
                }
            }
        }
        if(editing==-1) tooltipFont.drawStringCentred(pick(6,7).f,77f,442f,state.translator["rename"],Colour.white)
    }

    /** The achievements are drawn at their desktop positions, moved as a group. */
    private inline fun atAchievements(g: Graphics, draw: () -> Unit) {
        if (touch) {
            g.pushTransform()
            g.translate(HangarLayout.achievementDx.f,HangarLayout.achievementDy.f)
            draw()
            g.popTransform()
        } else draw()
    }

    /** A panel's iPad art, or the desktop's stretched over the same place when the data has none. */
    private fun drawTouchPanel(name: String,x: Int,y: Int,w: Int,h: Int) {
        val art=state.getImgOrNull("img/ipad/customizeUI/$name.png")
        if(art!=null) art.draw(x,y)
        else state.getImg("img/customizeUI/$name.png").let { it.draw(x.f,y.f,(x+w).f,(y+h).f,0f,0f,it.width.f,it.height.f) }
    }

    private fun drawNativeTextButton(g: Graphics, control: Control, inset: Int, fontId: Int, bgColour: Colour) {
        g.colour = bgColour
        Buttons.drawChamfer(g, control.x, control.y, control.w, control.h, inset)
        font(fontId).printButtonLabel(control.x, control.y, control.w, control.h, control.label, xyz.znix.xftl.hs.render.HsColors.tbText())
    }

    /** Three-slice panel: left half, a stretched middle column, then the rest of the art. */
    private fun drawShipSelectBox() {
        val img = state.getImg("img/customizeUI/box_shipselect.png")
        val left = img.width / 2
        val extra = shipBox.extra
        img.draw(0f, 106f, left.f, 106f + img.height, 0f, 0f, left.f, img.height.f)
        if (extra > 0) img.draw(left.f, 106f, (left + extra).f, 106f + img.height, left.f, 0f, left.f, img.height.f)
        img.draw((left + extra).f, 106f, (img.width + extra).f, 106f + img.height, left.f, 0f, img.width.f, img.height.f)
    }

    private fun drawAchievementBox() {
        // Located against the original: the panel starts one pixel off-screen.
        val img = state.getImg("img/customizeUI/box_shipach.png")
        val y = 355f
        img.draw(-1f, y, 119f, y + img.height, 0f, 0f, 120f, img.height.f)
        var x = 119
        repeat(achievementPadding / 10) {
            img.draw(x.f, y, (x + 10).f, y + img.height, 120f, 0f, 130f, img.height.f)
            x += 10
        }
        img.draw(x.f, y, (x + img.width - 120).f, y + img.height, 120f, 0f, img.width.f, img.height.f)
    }

    private fun drawAdvancedBox() {
        val img = state.getImg("img/customizeUI/box_advanced.png")
        val ae = advancedBox
        val y = pick(384,358).f
        val h = y + img.height
        var x = (advancedX + ae.start).f
        img.draw(x, y, x + 24, h, 0f, 0f, 24f, img.height.f); x += 24
        img.draw(x, y, x + ae.offWidth - 18, h, 24f, 0f, 24f, img.height.f); x += ae.offWidth - 18
        img.draw(x, y, x + 43, h, 115f, 0f, 158f, img.height.f); x += 43
        img.draw(x, y, x + ae.onWidth - 18, h, 158f, 0f, 158f, img.height.f); x += ae.onWidth - 18
        img.draw(x, y, x + img.width - 243, h, 243f, 0f, img.width.f, img.height.f)
    }

    /** A centred native text line with the TextInput cursor after [cursor] characters. */
    private fun drawNativeName(g: Graphics,value: String,centreX: Int,top: Int,active: Boolean,font: NativeFonts.NativeFont,colour: Colour=Colour.white) {
        font.printCenter(centreX.f,top.f,value,colour)
        // TextInput::OnRender (0x004ff2b0): a 2px (255,255,50) bar ending at the cursor,
        // from 10% to 90% of the measured line height.
        if(active && blink<0.5f) {
            val left=centreX-font.width(value)/2f
            val h=font.lineHeight
            g.colour=Colour(255,255,50)
            g.fillRect(left+font.advance(value.take(cursor))-2f,top+0.1f*h,2f,0.8f*h)
        }
    }

    private fun drawName(g: Graphics,value: String,x: Int,y: Int,width: Int,active: Boolean,font: SILFontLoader=small,centred: Boolean=false) {
        val left=if(centred) x+(width-font.getWidth(value).coerceAtMost(width))/2 else x
        val top=(y-font.baselineToTop*font.scale).toInt()
        font.drawStringTruncated(left.toFloat(),y.toFloat(),width.toFloat(),value,Colour.white)
        if(active && blink<0.5f) {g.colour=Constants.UI_BUTTON_HOVER;g.fillRect(left+font.getWidth(value.take(cursor)).coerceAtMost(width),top-8,2,(font.baselineToTop*font.scale+12).toInt())}
    }

    private fun drawCrew(g: Graphics) {
        if(isCustomizing) {
            // CrewCustomizeBox::OnRender (0x00545b40), relative to customizeLocation (100,542):
            // panel, Rename/Accept, name, 3x portrait, rename hint, then the arrows.
            state.getImg("img/customizeUI/box_crew_details.png").draw(100,542)
            val labels=controls().filter { it.art==null && it.id.startsWith("crew") }
            // Both labels fall back to font 12 when either is wider than 91px in font 62.
            val labelFont=if(labels.any { font(BUTTON_FONT).width(it.label)>91 }) 12 else BUTTON_FONT
            for(control in labels) {
                // CrewCustomizeBox keeps hovering its buttons while the name is edited.
                val over=control.contains(state.mousePos.x,state.mousePos.y)
                drawNativeTextButton(g,control,3,labelFont,if(over) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive())
            }
            drawNativeName(g,if(editing==customizing) text else crew[customizing].name,202,599,editing==customizing,font(14))
            // CrewCustomizeBox: RenderIcon at (205,579) scaled 3x; the 35px frame
            // starts 17 source pixels (51px) up and left (measured against the original).
            drawCrewSprite(customizing,154f,528f,105f,"portrait")
            if(editing==customizing) font(12).printCenter(197f,672f,state.translator["rename"],Colour.white)
            for(control in controls().filter { it.art!=null && it.id.startsWith("crew") }) {
                val over=!isEditing && control.contains(state.mousePos.x,state.mousePos.y)
                val img=state.getImg("img/customizeUI/${control.art}_${if(!control.enabled) "off" else if(over) "select2" else "on"}.png")
                if(control.id=="crewNext") {g.pushTransform();g.translate((control.x+img.width).toFloat(),control.y.toFloat());g.scale(-1f,1f);img.draw(0,0);g.popTransform()} else img.draw(control.x,control.y)
            }
            return
        }
        repeat(maxOf(4,crew.size)) { i ->
            val x=crewX(i);val y=crewY(i)
            // The card art (and its strip) highlight only over the card; hovering
            // Customize highlights just that button.
            val over=crewSlot()==i
            // The touch card has the Customize strip in its art.
            val touchCard=if(touch) state.getImgOrNull("img/ipad/upgradeUI/Equipment/box_crew_${if(i>=crew.size) "off" else if(over) "select2" else "on"}.png") else null
            if(touchCard!=null) touchCard.draw(x,y)
            else state.getImg("img/upgradeUI/Equipment/box_crew_${if(i>=crew.size) "off" else if(over) "selected" else "on"}.png").draw(x,y)
            if(i<crew.size) {
                // Located against the original: 2x portrait one right and two down.
                drawCrewSprite(i,x+16f,y-7f,70f,"portrait")
                // EquipmentBox::RenderLabels: crew names use font 6 centred at (50,49).
                // CrewMember::GetName returns the short name (CrewBlueprint::ShortenName).
                // Both take font 6 with ignoreLanguage for a name of bytes up to 0xD1 (NativeFonts.nameIgnoresLanguage).
                fun nameFont(s: String)=font(6).let { if(NativeFonts.nameIgnoresLanguage(s)) it.english else it }
                val shortName=names.shorten(crew[i].name) { nameFont(crew[i].name).measure(it) }
                drawNativeName(g,shortName,x+50,y+49,false,nameFont(shortName),Colour.white)
                val control=controls().first { it.id=="crew$i" }
                if(touchCard!=null) {
                    font(51).printButtonLabel(x+4,y+70,92,18,control.label,xyz.znix.xftl.hs.render.HsColors.tbText())
                    return@repeat
                }
                // CrewCustomizeBox (0x00544180/0x00545b40): the strip art sits at the inner
                // Customize button's location minus (4,8); the button is 92x12, font 51.
                state.getImg("img/customizeUI/box_crewcustom_${if(over) "selected" else "on"}.png").draw(x,y+62)
                val btnColour = if(control.contains(state.mousePos.x,state.mousePos.y)) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive()
                drawNativeTextButton(g, control.copy(x=x+4,y=y+70,w=92,h=12), 2, 51, btnColour)
            }
        }
    }

    private fun crewSlot(): Int? {
        if(isCustomizing) return null
        if(touch) return crew.indices.lastOrNull { i ->
            state.mousePos.x in crewX(i) until crewX(i)+100 && state.mousePos.y in crewY(i) until crewY(i)+93
        }
        return crew.indices.firstOrNull { i ->
            val x=70+(i%2)*160;val y=519+(i/2)*93
            state.mousePos.x in x until x+100 && state.mousePos.y in y until y+66
        }
    }

    private fun crewHint(): String? = crewSlot()?.let { crew[it].name+"\n"+crewDescription(crew[it]) }

    /** The room each starting crewmember stands in: the first free station, in this order. */
    fun crewRooms(): List<EditableRoom> {
        val rooms=state.previewEditor.ship.rooms
        val station=listOf("pilot","engines","weapons","shields").mapNotNull { type -> rooms.firstOrNull { it.system?.type==type } }
        val used=HashSet<EditableRoom>()
        return crew.map {
            val room=station.firstOrNull { it !in used } ?: rooms.first()
            used.add(room)
            room
        }
    }

    /** Rooms with Slug crew, whose telepathy also reveals the rooms their doors lead to. */
    fun slugRooms(): Set<EditableRoom> =
        crewRooms().filterIndexed { i, _ -> crew[i].species == "slug" }.toSet()

    /** Rooms with Lanius crew, who drain their oxygen. */
    fun anaerobicRooms(): Set<EditableRoom> =
        crewRooms().filterIndexed { i, _ -> crew[i].species == "anaerobic" }.toSet()

    fun drawShipCrew() {
        if(!state.roomsVisible)return
        val stations=crewRooms()
        crew.forEachIndexed { index,_ ->
            val room=stations[index]
            val type=room.system?.type
            val defaultSlot=when(type) {"pilot","shields"->0;"weapons"->1;"engines"->2;else->0}.coerceAtMost(room.w*room.h-1)
            val slot=room.system?.computerPoint ?: xyz.znix.xftl.math.ConstPoint(defaultSlot%room.w,defaultSlot/room.w)
            val x=slot?.x ?: 0;val y=slot?.y ?: 0
            val direction=room.system?.computerDirection?.name?.lowercase() ?: when(type) {"pilot"->"right";"shields"->"left";"weapons"->"up";"engines"->"down";else->null}
            val pose=direction?.let { "type_$it" } ?: "portrait"
            drawCrewSprite(index,(room.pixelX+x*35).toFloat(),(room.pixelY+y*35).toFloat(),35f,pose,true)
        }
    }

    private fun drawCrewSprite(i: Int,x: Float,y: Float,size: Float,pose: String,outlined: Boolean=false) {
            val c=crew[i]
            val bp=state.blueprints[c.species] as CrewBlueprint
            val female=c.species=="human" && c.colour>=bp.baseNumberOfColours
            val anim=state.animations.getOrNull("${c.species}_$pose") ?: state.animations.getOrNull("${c.species}_portrait") ?: return
            val frame=(elapsed/anim.time).toInt()%anim.length
            val path=if(female) "img/people/female_base.png" else anim.sheet.sheetPath
            // The ship's crew in the hangar carry the selected outline (ShipBuilder renders them highlighted).
            if (outlined) state.getImgOrNull("img/people/${c.species}_color.png")?.let {
                anim.sheet.getSprite(it,anim.x+frame,anim.y).draw(x,y,size,size,HANGAR_CREW_OUTLINE)
            }
            val img=anim.sheet.getSprite(state.getImg(path),anim.x+frame,anim.y)
            img.draw(x,y,size,size)
            val layers=if(female) bp.femaleLayerImageNames else bp.layerImageNames
            layers.forEachIndexed { index,p ->
                val colour=bp.colourFilters[index][(c.colour%bp.baseNumberOfColours)%bp.colourFilters[index].size]
                anim.sheet.getSprite(state.getImg(p),anim.x+frame,anim.y).draw(x,y,size,size,colour)
            }
    }

    private fun drawEquipment(g: Graphics) {
        val bp=state.blueprint
        val weaponsInstalled=bp.systems.any { it.systemName=="weapons" && it.availableByDefault }
        val dronesInstalled=bp.systems.any { it.systemName=="drones" && it.availableByDefault }
        val weaponsX=pick(435,257);val weaponsY=pick(514,562);val dronesY=pick(624,672)
        // ShipBuilder::OnRender: font 63 centred at (662,539)/(662,649) in the idle button colour.
        if(!weaponsInstalled) font(63).printCenter(pick(662,484).f,pick(539,585).f,state.translator["equipment_no_system"],Constants.TEXT_BUTTON_NORMAL)
        if(!dronesInstalled) font(63).printCenter(pick(662,484).f,pick(649,695).f,state.translator["equipment_no_system"],Constants.TEXT_BUTTON_NORMAL)
        // Hyperspace showDummyEquipmentSlots: the empty slot art of a missing system, at the configured opacity.
        if (hs != null && hs.dummySlots()) {
            if (!weaponsInstalled) for (i in 0 until (bp.weaponSlots?:4)) state.getImg("img/upgradeUI/Equipment/box_weapons_off.png").draw(weaponsX+(4-(bp.weaponSlots?:4))*58+i*117,weaponsY,hs.dummyOpacity())
            if (!dronesInstalled) for (i in 0 until (bp.droneSlots?:2)) state.getImg("img/upgradeUI/Equipment/box_drones_off.png").draw(weaponsX+(4-(bp.droneSlots?:2))*58+i*117,dronesY,hs.dummyOpacity())
        }
        for(i in 0 until (if(weaponsInstalled) bp.weaponSlots?:4 else 0)) {
            val x=weaponsX+(4-(bp.weaponSlots?:4))*58+i*117
            val weapon=bp.initialWeapons.getOrNull(i)?.let { state.blueprints.getOrNull(it) as? AbstractWeaponBlueprint }
            val card=state.getImg("img/upgradeUI/Equipment/box_weapons_${if(weapon==null) "off" else if(equipmentSlot()=="weapon$i") "selected" else "on"}.png")
            if(weapon==null) card.draw(x,weaponsY) else BlueprintSelector.drawWeaponCard(g,state.previewEditor,x,weaponsY,weapon,card)
        }
        for(i in 0 until (if(dronesInstalled) bp.droneSlots?:2 else 0)) {
            val x=weaponsX+(4-(bp.droneSlots?:2))*58+i*117
            val drone=bp.initialDrones.getOrNull(i)?.let { state.blueprints.getOrNull(it) as? DroneBlueprint }
            val card=state.getImg("img/upgradeUI/Equipment/box_drones_${if(drone==null) "off" else if(equipmentSlot()=="drone$i") "selected" else "on"}.png")
            if(drone==null) card.draw(x,dronesY) else BlueprintSelector.drawDroneCard(g,state.previewEditor,x,dronesY,drone,card)
        }
        // Hyperspace pages the augment boxes three at a time (CreateEquipmentBoxes' augSlots).
        val augments=if (hs != null) hs.augmentRange().toList() else (0 until 3).toList()
        for(slot in augments) { val i=slot%3
            val item=bp.hangarAugments.getOrNull(slot)?.let { state.blueprints.getOrNull(it) as? Blueprint }
            val x=pick(988,752);val y=pick(529,577)+i*60
            state.getImg("img/upgradeUI/Equipment/box_augment_${if(item==null) "off" else if(equipmentSlot()=="augment$slot") "selected" else "on"}.png").draw(x,y)
            // Augment labels: font 13 centred at (117,14), white (EquipmentBox::RenderLabels).
            if(item!=null) font(13).printCenter(x+117f,y+14f,item.title?.let { state.translator[it] } ?: item.name,Colour.white)
            // Hyperspace locked augments (CustomAugments.cpp EquipmentBox::OnRender): the padlock left of the box.
            if(item!=null && hs != null && xyz.znix.xftl.hs.ship.HsAugments.isLocked(item.name)) state.getImgOrNull("img/upgradeUI/Equipment/aug_lock.png")?.draw(x-24,y+7)
        }
    }

    private fun equipmentSlot(): String? {
        val x=state.mousePos.x;val y=state.mousePos.y;val bp=state.blueprint
        for((kind,slots,top) in listOf(Triple("weapon",bp.weaponSlots?:4,pick(514,562)),Triple("drone",bp.droneSlots?:2,pick(624,672)))) {
            for(i in 0 until slots) {val left=pick(435,257)+(4-slots)*58+i*117;if(x in left until left+109 && y in top until top+76)return "$kind$i"}
        }
        val augments=if (hs != null) hs.augmentRange().toList() else bp.hangarAugments.indices.toList()
        val augmentX=pick(988,752);val augmentY=pick(529,577)
        for(i in augments) if(i<bp.hangarAugments.size && x in augmentX until augmentX+232 && y in augmentY+(i%3)*60 until augmentY+46+(i%3)*60) return "augment$i"
        return null
    }

    private fun equipmentHint(): String? {
        val item=equipmentItem() ?: return null
        val lines=arrayListOf(item.title?.let { state.translator[it] } ?: item.name,item.desc?.let { state.translator[it] } ?: "")
        if(item is AbstractWeaponBlueprint) lines += "Power: ${item.power}   Charge time: ${item.chargeTime}s"
        if(item is DroneBlueprint) lines += "Power: ${item.power}"
        return lines.joinToString("\n")
    }

    private fun equipmentItem(): Blueprint? {
        val key=equipmentSlot() ?: return null;val bp=state.blueprint
        return when {key.startsWith("weapon")->bp.initialWeapons.getOrNull(key.removePrefix("weapon").toInt());key.startsWith("drone")->bp.initialDrones.getOrNull(key.removePrefix("drone").toInt());else->bp.hangarAugments.getOrNull(key.removePrefix("augment").toInt())}?.let { state.blueprints.getOrNull(it) as? Blueprint }
    }

    private fun shipHint(): String? {
        if(state.mousePos.x !in pick(110,111) until pick(442,443) || state.mousePos.y !in 8 until 56)return null
        return listOfNotNull(state.blueprint.shipClass?.let { state.translator[it] },state.blueprint.desc?.let { state.translator[it] }).joinToString("\n")
    }

    private fun drawAchievements(g: Graphics) {
        val family=state.shipFamilies.byShipId[state.selectedShipId] ?: return
        family.achievements.forEachIndexed { i,id ->
            val ach=state.achievements[id];val earned=state.profile.getAchievement(ach)
            val x=21+i*70;val y=404
            state.getImg(ach.img).draw(x,y)
            if(earned==null) {
                // CAchievement::lockOverlay is black at 0.75 alpha (live native primitive).
                g.colour=Colour(0f,0f,0f,0.75f);g.fillRect(x,y,64,64)
                state.getImg("img/customizeUI/box_lock_on.png").draw(x+24,y+21)
            }
            g.colour=if(achievementSlot()=="achievement$i") Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT else Constants.ACHIEVEMENT_OUTLINE
            g.drawRect(x,y,63,63);g.drawRect(x+1,y+1,61,61)
        }
        val wins=family.ships.map { state.profile.getLayoutVictory(it) }
        state.getImg("img/achievements/S_V_${if(wins.any { it!=null }) "on" else "off"}.png").draw(244,438)
        // CAchievement::OnRender: dots 24px down, +1 (or +6 for two-layout families).
        val dotX=244+(if(family.ships.size<3) 6 else 1)
        if(wins.any { it!=null }) family.ships.forEachIndexed { i,_ -> state.getImg("img/achievements/S_V_dot_${if(wins[i]!=null) "on" else "off"}.png").draw(dotX+i*10,462) }
        if(family.hasQuest) {
            val earned=family.unlockId!=null && state.profile.getShipUnlock(family)!=null
            state.getImg("img/achievements/S_Q_${if(earned) "on" else "off"}.png").draw(244,404)
        }
    }

    private fun achievementSlot(): String? {
        if (hs != null) return hs.achievementSlot()
        val x=state.mousePos.x-HangarLayout.achievementDx;val y=state.mousePos.y-HangarLayout.achievementDy
        if(y !in 399 until 464)return null
        if(x in 244 until 276) return if(y>=438) "victory" else if(state.shipFamilies.byShipId[state.selectedShipId]?.hasQuest==true) "quest" else null
        for(i in 0..2)if(x in 21+i*70 until 85+i*70)return "achievement$i"
        return null
    }

    private fun achievementHint(): String? {
        val key=achievementSlot() ?: return null
        if (hs != null) {
            hs.achievementHint(key)?.let { return it }
            // Vanilla ships keep the original three achievements, victory and quest.
            val family=state.shipFamilies.byShipId[state.selectedShipId] ?: return null
            if(key=="victory" || key=="quest") return familyAchievementHint(family,key)
            return family.achievements.getOrNull(key.removePrefix("achievement").toInt())?.let { achievementHint(it) }
        }
        if(key=="victory" || key=="quest") return familyAchievementHint(state.shipFamilies.byShipId[state.selectedShipId]!!,key)
        val id=state.shipFamilies.byShipId[state.selectedShipId]?.achievements?.getOrNull(key.removePrefix("achievement").toInt()) ?: return null
        return achievementHint(id)
    }

    fun achievementHint(id: String): String {
        val a=state.achievements[id]
        val earned=state.profile.getAchievement(a)
        return state.translator[a.name]+"\n"+state.translator[a.desc]+(earned?.let { "\n\n"+state.translator["ach_"+it.difficulty.name.lowercase()] } ?: "")
    }

    fun familyAchievementHint(family: xyz.znix.xftl.game.ShipFamily,kind: String): String {
        val title=state.catalogBlueprints.getShip(family.ships.first()).shipClass?.let { state.translator[it] } ?: family.ships.first()
        fun format(key: String)=state.translator[key].replace("\\1",title)
        val lines= arrayListOf(format(if(kind=="quest") "quest_ach_title" else "victory_ach_title"),format(if(kind=="quest") "quest_ach" else "victory_ach"))
        if(kind=="victory") family.ships.forEachIndexed { i,id -> state.profile.getLayoutVictory(id)?.let {
            lines+="\n"+state.translator["achieved_with"].replace("\\1",state.translator["type_"+('a'+i)]).replace("\\2",state.translator[it.difficulty.name.lowercase()])
        } }
        return lines.joinToString("\n")
    }

    private fun installedSystems() = state.previewEditor.ship.rooms.mapNotNull { it.system }.filter { it.startingPower>0 }.sortedBy {
        listOf("shields","engines","oxygen","weapons","drones","medbay","pilot","sensors","doors","teleporter","cloaking","artillery","battery","clonebay","mind","hacking").indexOf(it.type)
    }

    private fun drawSystems(g: Graphics) {
        installedSystems().forEachIndexed { i,system ->
            val x=373+i*38;val dy=HangarLayout.systemDy
            state.getImg("img/customizeUI/box_system_${if(systemSlot()=="strip$i") "select2" else "on"}.png").draw(x,380+dy)
            val icon=state.getImg(system.getBP(state).onIconPath)
            icon.draw(x+(38-icon.width)/2,438+dy+(38-icon.height)/2)
            // SystemBox power boxes: 16x6 at the box location (x-13) + 24, normal power colour.
            repeat(system.startingPower) { level ->
                g.colour=Constants.SYS_ENERGY_ACTIVE;g.fillRect(x+11,433+dy-level*8,16,6)
            }
        }
    }

    private fun systemSlot(): String? {
        if(state.mousePos.y-HangarLayout.systemDy in 401 until 475) installedSystems().forEachIndexed { i,_ ->
            if(state.mousePos.x in 373+i*38 until 411+i*38)return "strip$i"
        }
        val x=state.mousePos.x-state.shipOffset.x;val y=state.mousePos.y-state.shipOffset.y
        if(!state.roomsVisible)return null
        val i=state.previewEditor.ship.rooms.indexOfFirst { it.system!=null && x in it.pixelX until it.pixelX+it.pixelWidth && y in it.pixelY until it.pixelY+it.pixelHeight }
        return if(i<0) null else "system$i"
    }

    private fun hoveredSystem(): EditableSystem? {
        val slot=systemSlot() ?: return null
        return if(slot.startsWith("strip")) installedSystems().getOrNull(slot.removePrefix("strip").toInt())
        else state.previewEditor.ship.rooms[slot.removePrefix("system").toInt()].system
    }

    private fun systemHint(): String? {
        val s=hoveredSystem() ?: return null
        val bp=s.getBP(state)
        return "${bp.title?.let { state.translator[it] } ?: bp.name}\n${bp.desc?.let { state.translator[it] } ?: ""}\nPower: ${s.startingPower}"
    }

    fun drawTooltip(g: Graphics,content: String,x: Int,y: Int,maxWidth: Int?=null) {
        // The first line is the tooltip title when the hint has more than one.
        val hasTitle=content.contains('\n')
        val title=if(hasTitle) content.substringBefore('\n') else null
        val body=if(hasTitle) content.substringAfter('\n') else content
        // Only the 1280 wide frame's edge is known to the tooltip: keep it on the touch block too.
        val left=if(touch) x.coerceAtMost(xyz.znix.xftl.game.TouchMode.HUD_W+xyz.znix.xftl.game.TouchMode.hudX-NativeTooltip.measure(if(hasTitle) tooltipTitleFont else null,tooltipFont,title,body,maxWidth).first-4) else x
        NativeTooltip.draw(g,if(hasTitle) tooltipTitleFont else null,tooltipFont,left,y,title,body,maxWidth)
    }

    private fun drawInfoBox(title: String,description: String) {
        infoPanel.position=infoAnchor
        infoPanel.drawDescriptionBox(GameText.literal(title),GameText.literal(description),null,emptyList(),xyz.znix.xftl.game.InfoPanel.INFO_HEIGHT_SYSTEM)
    }

    /**
     * ShipSelect's hover InfoBox: SetDescription(desc, 360, height, 1) at (453,460),
     * where height is 176 for a locked Type A ship and 135 otherwise. The box
     * sits 0x43 below that point and its frame is inset 7px on every side.
     * The touch list has it under its type tabs, right of the lower cards.
     */
    fun drawShipListInfoBox(title: String,description: String,height: Int,touchList: Boolean=false) {
        infoPanel.position=if(touchList) xyz.znix.xftl.math.ConstPoint(540,530) else xyz.znix.xftl.math.ConstPoint(460,534)
        infoPanel.customWidth=360
        infoPanel.drawDescriptionBox(GameText.literal(title),GameText.literal(description),null,emptyList(),height-14)
        infoPanel.customWidth=null
    }

    fun drawInformation(title: String,description: String,x: Int,y: Int,width: Int,minHeight: Int=0) {
        val titleFont=state.getFont("c&cnew").apply { scale=2f }
        val descriptionFont=state.getFont("JustinFont10")
        val titleLines=titleFont.wrapString(title,width-22)
        val lines=description.split('\n').flatMap { descriptionFont.wrapString(it,width-22) }
        val height=maxOf(minHeight,30+titleLines.size*20+lines.size*17+14)
        val top=y.coerceAtMost(720-height-7)
        state.windowRenderer.render(x,top,width,height)
        var baseline=top+30f
        titleLines.forEach { titleFont.drawString(x+11f,baseline,it,Colour.white);baseline+=20f }
        baseline+=5f
        lines.forEach { descriptionFont.drawString(x+11f,baseline,it,Colour.white);baseline+=17f }
    }

    private companion object {
        /** HL2 at 2x: the hangar's TextButton font. */
        const val BUTTON_FONT = 62
        /** The touch layout's ship select art. */
        const val TOUCH_SHIP_BOX_WIDTH = 233
    }
}

private val HANGAR_CREW_OUTLINE = xyz.znix.xftl.rendering.Colour(1f, 1f, 50 / 255f, 1f)
