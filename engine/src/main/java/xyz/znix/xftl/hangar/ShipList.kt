package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Original progression grid, with a single layout selector for all families. With [touch] (the hangar of the touch
 * layout, not the statistics screen's list on the 1280x720 frame) it takes the iPad layout on the 1024x768 block: two
 * panels, the last two cards below the rest, and a tap shows the ship in the hangar until ACCEPT or a tap outside.
 */
class ShipList(private val state: SelectShipState, private val touch: Boolean = false, private val close: (LazyShipBlueprint?) -> Unit) {
    private val titleFont = state.getFont("HL2").apply { scale=3f }
    private val buttonFont = state.getFont("HL2").apply { scale=2f }
    private val labelFont = xyz.znix.xftl.rendering.NativeFonts.font(state::getFont, 62)
    private val absentFont = xyz.znix.xftl.rendering.NativeFonts.font(state::getFont, 12)
    private val order = listOf("PLAYER_SHIP_HARD","PLAYER_SHIP_CIRCLE","PLAYER_SHIP_FED","PLAYER_SHIP_ENERGY",
        "PLAYER_SHIP_STEALTH","PLAYER_SHIP_ROCK","PLAYER_SHIP_JELLY","PLAYER_SHIP_MANTIS",
        "PLAYER_SHIP_ANAEROBIC","PLAYER_SHIP_CRYSTAL")
    private var layout = 0
    private data class Cell(val family: ShipFamily,val x: Int,val y: Int,val custom: Boolean = false)

    // Hyperspace (CustomShipSelect): page 0 is the vanilla grid, pages 1..max hold ten custom entries each.
    private val hs = xyz.znix.xftl.hs.HS.active
    private val hsUi = if (hs) xyz.znix.xftl.hs.render.HsListUi(state, touch) else null
    // HS CustomShipSelect (1b05a07 CustomShipSelect.cpp: "if (hideFirstPage && shipPage == 0) shipPage = 1" after parsing)
    private var page = if (hs && hideFirstPage) 1 else 0
    val diagnosticPage get() = page
    /** Names of the cards on the current page (diagnostics). */
    val diagnosticCards get() = cells().map { it.family.ships.first() }
    private val maxPage get() = xyz.znix.xftl.hs.ship.HsHangar.maxPage()
    private val hideFirstPage get() = xyz.znix.xftl.hs.ship.HsShips.hideFirstPage

    /** Where the two rows of four cards and the two further cards go. */
    private fun rowCard(column: Int, row: Int) = if (touch) Pair(ORIGIN_X + 24 + 205 * column, ORIGIN_Y + 51 + 177 * row) else Pair(136 + 205 * column, 161 + 177 * row)
    private fun extraCard(i: Int) = if (touch) Pair(ORIGIN_X + 18 + 208 * i, ORIGIN_Y + 452) else Pair(976, 161 + 177 * i)

    private fun cells(): List<Cell> {
        if (hs && page > 0) {
            val order = xyz.znix.xftl.hs.ship.HsHangar.order()
            val result = ArrayList<Cell>()
            for (onPage in 0 until 10) {
                val name = order.getOrNull((page - 1) * 10 + onPage) ?: break
                val family = state.shipFamilies.byShipId[name] ?: continue
                // CustomShipSelect::OnInit: top row left to right, bottom row right to left, then the right column.
                val (x, y) = when {
                    onPage < 4 -> rowCard(onPage, 0)
                    onPage < 8 -> rowCard(3 - (onPage - 4), 1)
                    else -> extraCard(onPage - 8)
                }
                result += Cell(family, x, y, true)
            }
            return result
        }
        return order.mapIndexedNotNull { i,id ->
            val family=state.shipFamilies.byShipId[id] ?: return@mapIndexedNotNull null
            val (x,y)=if(i<8) rowCard(i%4,i/4) else extraCard(i-8)
            Cell(family,x,y)
        }
    }
    private fun ship(cell: Cell) = cell.family.ships.getOrNull(layout)
        ?.takeIf { !cell.custom || xyz.znix.xftl.hs.ship.HsHangar.variantExists(it) }
        ?.let { id -> state.catalogShips.firstOrNull { it.name==id } }
    private fun available(cell: Cell) = ship(cell)?.let { bp -> state.ships.any { it.name==bp.name } } == true
    private fun unlocked(cell: Cell) = ship(cell)?.let { state.isShipUnlocked(it.name) } == true
    // A custom card is locked per layout (ShipButton::bShipLocked of the current type).
    private fun familyUnlocked(cell: Cell) = if (cell.custom) unlocked(cell) else state.isShipUnlocked(cell.family.ships.first())
    private fun cardY(cell: Cell) = cell.y+if(familyUnlocked(cell)) 0 else 20
    private fun cellAt(x: Int,y: Int) = cells().firstOrNull {
        x in it.x until it.x+191 && y in cardY(it) until (if(familyUnlocked(it)) it.y+174 else cardY(it)+121)
    }
    private val widestTab get() = (0..2).maxOf { buttonFont.getWidth(state.translator["typebutton_type_"+('a'+it)]) }
    private val tabWidth get() = widestTab + if (touch) 6 else 4
    private val tabStride get() = if (touch) widestTab + 26 else tabWidth + 4
    private val tabLeft get() = if (touch) ORIGIN_X + 448 else 685
    private val tabTop get() = if (touch) ORIGIN_Y + 437 else 122
    private val tabHeight get() = if (touch) 33 else 22
    /**
     * The type tab and the Hyperspace page arrow under the pointer. ShipSelect::MouseMove (1.6.9 0x513bb0) only moves
     * the tutorial box while it is open and Hyperspace's CustomShipSelect::MouseMove returns then: no hover under it.
     */
    private val hoveredTab get() = if (tutorial != null) null else tabAt(state.mousePos.x, state.mousePos.y)
    private val hoveredPageButton get() = if (tutorial != null) null else pageButtonAt(state.mousePos.x, state.mousePos.y)
    val diagnosticButtonHover get() = "tab=$hoveredTab page=$hoveredPageButton"
    private fun tabAt(x: Int,y: Int) = if(y in tabTop until tabTop+tabHeight) (0..2).firstOrNull { x in tabLeft+it*tabStride until tabLeft+it*tabStride+tabWidth } else null
    // The tutorial box is laid out for the 1280x720 frame: on the block it moves to the middle.
    private val tutorialDx get() = if (touch) TouchMode.HUD_W / 2 - 640 else 0
    private val tutorialDy get() = if (touch) TouchMode.HUD_H / 2 - 360 else 0
    // The Hyperspace page buttons stay at the top right corner of the panel.
    private val pageDx get() = if (touch) -254 else 0
    private val pageDy get() = if (touch) -60 else 0
    /** The family whose card was tapped last on touch: its text stays shown. */
    private var tapped: ShipFamily? = null
    private var hover: String? = null
    // ShipSelect::tutorial: shown on opening until seen, then a second page.
    private var tutorial: xyz.znix.xftl.game.NativeChoiceBox? = null
    private var tutorialPage = 0

    /** ShipSelect::Open (0x00529940): the tutorial opens unless already shown. */
    fun open() {
        tapped = null
        if (hs) {
            // ShipSelect::Open hook: the page of the current custom ship; HS never shows a hidden first page.
            val current = xyz.znix.xftl.hs.ship.HsHangar.variantOf(state.selectedShipId)
            val p = current?.let { xyz.znix.xftl.hs.ship.HsHangar.pageOf(it.first.name) } ?: -1
            page = if (p > 0) p else if (hideFirstPage && page == 0) 1 else page
            layout = current?.second ?: layout
        }
        // The touch list shows the hangar's ship, which is one of its cards in its own layout.
        if (touch) state.shipFamilies.byShipId[state.selectedShipId]?.ships?.indexOf(state.selectedShipId)?.takeIf { it >= 0 }?.let { layout = it }
        val profile=state.profile
        if(profile.listTutorialState!=2) {
            tutorial=xyz.znix.xftl.game.NativeChoiceBox(state::getFont,state.windowRenderer,state.translator["list_tutorial"],
                listOf(xyz.znix.xftl.game.NativeChoiceBox.Choice(state.translator["continue"])))
            tutorialPage=0
            profile.listTutorialState=2
            profile.markDirty()
        }
    }

    /** ShipSelect::OnLoop: the first choice turns to the second page, then closes. */
    private fun chooseTutorial(choice: Int?) {
        if(choice==null) return
        if(tutorialPage==0) {
            tutorialPage=1
            tutorial?.text=state.translator[if (touch) "list_tutorial_2_ipad" else "list_tutorial_2"]
        } else tutorial=null
    }

    val isTutorialOpen get() = tutorial!=null

    fun keyPressed(c: Char) { tutorial?.let { chooseTutorial(it.key(c)) } }
    private fun decorationAt(cell: Cell): String? {
        if(!familyUnlocked(cell) || state.mousePos.y !in cell.y+123 until cell.y+155) return null
        val x=state.mousePos.x-cell.x
        return when {
            x in 6 until 117 -> "achievement"+((x-6)/37)
            x in 117 until 149 -> "victory"
            x in 154 until 186 && cell.family.hasQuest -> "quest"
            else -> null
        }
    }

    fun update() {
        tutorial?.let { it.mouseMove(state.mousePos.x-tutorialDx,state.mousePos.y-tutorialDy);hover=null;return }
        val cell=cellAt(state.mousePos.x,state.mousePos.y)
        val tab=tabAt(state.mousePos.x,state.mousePos.y)
        val next=tab?.let { "tab"+it } ?: cell?.let { it.family.ships.first()+":"+(decorationAt(it) ?: "ship") }
        if(next!=hover) {
            hover=next
            if(cell!=null || (tab!=null && tab!=layout)) state.sounds.getSample("hoverBeep").play()
        }
    }

    fun draw(container: GameContainer,g: Graphics) {
        // ShipSelect::OnRender: the hangar is dimmed to a quarter, and the whole
        // list is tinted to half brightness while its tutorial is open.
        g.colour=Colour(0f,0f,0f,0.75f);g.fillRect(if (touch) -TouchMode.hudX else 0,0,container.width,container.height)
        if(tutorial!=null) Graphics.tint=Colour(0.5f,0.5f,0.5f,1f)
        if (touch) drawTouchPanels() else state.getImg("img/customizeUI/ship_list_main_2.png").draw(112,110)
        // ShipSelect::titlePos (359,118), font 63.
        titleFont.drawStringFromTop(if (touch) ORIGIN_X+247f else 359f,if (touch) ORIGIN_Y+8f else 118f,state.translator["shiplist_title"],Constants.JUMP_DISABLED_TEXT)
        if (touch) drawTouchTabFrames() else drawTabFrame()
        for(i in 0..2) {
            val x=tabLeft+i*tabStride
            val over=hoveredTab==i
            val isSelected=i==layout
            val bgColour = when {
                isSelected -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
                over -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
                else -> xyz.znix.xftl.hs.render.HsColors.tbActive()
            }
            g.colour=bgColour
            Buttons.drawChamfer(g,x,tabTop,tabWidth,tabHeight,3)
            labelFont.printButtonLabel(x,tabTop,tabWidth,tabHeight,state.translator["typebutton_type_"+('a'+i)],xyz.znix.xftl.hs.render.HsColors.tbText())
        }
        // No card takes the hover while the tutorial is open.
        val hovered=if(tutorial!=null) null else cellAt(state.mousePos.x,state.mousePos.y)
        // On touch the hangar's ship stays highlighted, and its text or the tapped card's shows without a finger down.
        val shown=if(touch && tutorial==null) cells().firstOrNull { ship(it)?.name==state.selectedShipId } else null
        // Hyperspace draws the ship cards and the filter box after the original list, over its tutorial box (oracle ph-w1).
        val tutorialUnder=hs && !touch
        if (tutorialUnder) tutorial?.draw(g)
        cells().forEach { cell ->
            val blueprint=ship(cell)
            val open=familyUnlocked(cell);val over=hovered==cell;val y=cardY(cell)
            // ShipSelect::OnRender would give a list-selected card select2 art,
            // but Open clears that selection, so only hovering highlights.
            val highlighted=blueprint!=null && (over || cell==shown)
            // ShipButton::OnRender (0x0052bce0): the lower strip and its icons are
            // drawn first, 107px down, and the card overlaps the strip's top.
            if(open && blueprint!=null) {
                state.getImg("img/customizeUI/ship_list_button_lower_"+(if(highlighted) "select2" else "on")+".png").draw(cell.x,y+107)
                drawAchievementIcons(g,cell,y)
            }
            val suffix=if(blueprint==null) "off" else if(highlighted) "select2" else "on"
            state.getImg("img/customizeUI/ship_list_button_"+suffix+".png").draw(cell.x,y)
            if(blueprint==null) {
                absentFont.printCenter(cell.x+95f,y+50f,state.translator["no_type_c"],Colour.white)
                return@forEach
            }
            // A locked ship is drawn normally, then again in black over itself.
            state.getImgOrNull(hsUi?.miniship(blueprint.img,!unlocked(cell)) ?: "img/customizeUI/miniship_"+blueprint.img+".png")?.let {
                it.draw(cell.x,y)
                if(!unlocked(cell)) it.draw(cell.x,y,Colour.black)
            }
            if(!unlocked(cell)) state.getImg("img/customizeUI/box_lock_"+(if(over) "selected" else "on")+".png").draw(cell.x+90,y+50)
        }
        if (!hs || page == 0) drawArrows(g) else drawCustomArrows(g)
        if (hs) drawPageButtons(g)
        if (touch && tutorial!=null) drawAccept(g)
        Graphics.tint=Colour.white
        val tappedCell=if(tutorial==null) cells().firstOrNull { it.family==tapped } else null
        // Hyperspace's victory-filter box takes the info box's place while no card is hovered.
        val infoCell=if(!touch) hovered else hovered ?: tappedCell ?: if(hsUi?.hasFilters==true) null else shown
        val infoShown=infoCell?.let { ship(it) } != null
        val filterTip=hsUi?.let { if(tutorial==null) it.hover(infoShown) else null }
        hsUi?.draw(g,infoShown || (tutorial!=null && !tutorialUnder))
        if (tutorialUnder && tutorial!=null) return
        tutorial?.let {
            if (touch) {
                g.pushTransform();g.translate(tutorialDx.toFloat(),tutorialDy.toFloat())
                it.draw(g)
                g.popTransform()
            } else it.draw(g)
            return
        }
        filterTip?.let { state.hangarPresentation.drawTooltip(g,it,state.mousePos.x,state.mousePos.y) }
        infoCell?.let { cell ->
            // The InfoBox follows the hovered card (its strip included); an
            // achievement icon's tooltip is drawn over it.
            ship(cell)?.let { blueprint ->
                val hidden=cell.family.ships.first()=="PLAYER_SHIP_CRYSTAL" && !state.isShipUnlocked(cell.family.ships.first())
                val title=if(hidden) state.translator["unidentified"] else blueprint.shipClass?.let { state.translator[it] } ?: blueprint.name
                // text_misc's suffix is " \1" (Chinese "\1"), its leading space kept.
                val suffix=state.translator["shipselect_type_suffix"].replace("\\1",state.translator["type_"+('a'+layout)])
                val description=if(unlocked(cell)) blueprint.real.desc?.let { state.translator[it] } ?: "" else state.lockReasonFor(blueprint.name)
                state.hangarPresentation.drawShipListInfoBox(title+suffix,description,if(!unlocked(cell) && layout==0) 176 else 135,touch)
            }
            val decoration=decorationAt(cell) ?: return@let
            val hint=if(decoration.startsWith("achievement")) cell.family.achievements.getOrNull(decoration.removePrefix("achievement").toInt())?.let { state.hangarPresentation.achievementHint(it) }
            else state.hangarPresentation.familyAchievementHint(cell.family,decoration)
            hint?.let { state.hangarPresentation.drawTooltip(g,it,state.mousePos.x,state.mousePos.y) }
        }
        if (touch) drawAccept(g)
    }

    /** The panels' iPad art; without it, the part of the desktop's panel that holds the rows of cards. */
    private fun drawTouchPanels() {
        val upper=state.getImgOrNull("img/ipad/customizeUI/ship_list_main_up.png")
        if(upper!=null) upper.draw(ORIGIN_X,ORIGIN_Y)
        else state.getImg("img/customizeUI/ship_list_main_2.png").draw(ORIGIN_X.toFloat(),ORIGIN_Y.toFloat(),ORIGIN_X+853f,ORIGIN_Y+412f,0f,0f,853f,412f)
        state.getImgOrNull("img/ipad/customizeUI/ship_list_main_down.png")?.draw(ORIGIN_X,ORIGIN_Y+440)
    }

    private fun drawTouchTabFrames() {
        val img=state.getImgOrNull("img/ipad/customizeUI/button_type_box.png") ?: return
        for(i in 0..2) xyz.znix.xftl.game.TouchArt.frame(img,tabLeft+i*tabStride-13,tabTop-13,tabWidth+26,tabHeight+26,13)
    }

    private fun overAccept(x: Int,y: Int) = x in ACCEPT_X until ACCEPT_X+ACCEPT_WIDTH && y in ACCEPT_Y until ACCEPT_Y+ACCEPT_HEIGHT

    private fun drawAccept(g: Graphics) {
        state.getImgOrNull("img/upgradeUI/buttons_accept_base.png")?.draw(ACCEPT_X-23,ACCEPT_Y-7)
        g.colour=if(tutorial==null && overAccept(state.mousePos.x,state.mousePos.y)) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive()
        Buttons.drawChamfer(g,ACCEPT_X,ACCEPT_Y,ACCEPT_WIDTH,ACCEPT_HEIGHT,4)
        labelFont.printButtonLabel(ACCEPT_X,ACCEPT_Y,ACCEPT_WIDTH,ACCEPT_HEIGHT,state.translator["shiplist_accept_button"],xyz.znix.xftl.hs.render.HsColors.tbText())
    }

    /** A tap on the touch list: a type shows that layout of the hangar's ship, a card shows its ship, and outside closes. */
    private fun touchClick(x: Int,y: Int) {
        tabAt(x,y)?.let { tab ->
            layout=tab;hover=null
            cells().firstOrNull { it.family==state.shipFamilies.byShipId[state.selectedShipId] }?.let { preview(it) }
            return
        }
        if(overAccept(x,y)) {close(null);return}
        if(x<50 || x>974 || y<50 || (x<512 && y>718) || (x>512 && y>668)) {close(null);return}
        val cell=cellAt(x,y)
        tapped=cell?.family
        if(cell!=null) preview(cell)
    }

    private fun preview(cell: Cell) {
        val blueprint=ship(cell) ?: return
        if(available(cell) && state.isShipUnlocked(blueprint.name)) state.previewShip(blueprint)
    }

    private fun drawTabFrame() {
        // Native00529940 stretches the shared frame's one-pixel strips, retaining
        // its12px caps and24px dividers. The button fills sit3px inside it.
        val img=state.getImg("img/customizeUI/type_button_frame.png")
        var x=682f
        fun segment(width: Int, left: Int, right: Int) {
            img.draw(x,119f,x+width,147f,left.toFloat(),0f,right.toFloat(),28f)
            x+=width
        }
        segment(12,0,12)
        segment(tabWidth-19,12,13)
        segment(24,13,37)
        segment(tabWidth-20,12,13)
        segment(24,13,37)
        segment(tabWidth-19,37,38)
        segment(12,38,50)
    }

    /** CAchievement::OnRender for the strip: 37px apart from (+6,+123); victory dots 24px down. */
    private fun drawAchievementIcons(g: Graphics,cell: Cell,y: Int) {
        val hover=decorationAt(cell)
        fun icon(x: Int,path: String,key: String) {
            state.getImg(path).draw(x,y+123)
            if(hover==key) {
                g.colour=Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT
                g.drawRect(x,y+123,31,31);g.drawRect(x+1,y+124,29,29)
            }
        }
        val filters=hsUi?.filtered().orEmpty()
        if(filters.isNotEmpty()) drawFilteredIcons(g,cell,y,filters) else
        cell.family.achievements.forEachIndexed { i,id ->
            val earned=state.profile.getAchievement(state.achievements[id])!=null
            icon(cell.x+6+i*37,"img/achievements/S_"+(i+1)+"_"+(if(earned) "on" else "off")+".png","achievement$i")
        }
        // Hyperspace cards: the entry's victory per layout (CustomShipUnlocks::shipVictories), only existing layouts.
        val wins: List<Any?> = if (cell.custom) cell.family.ships.filter { xyz.znix.xftl.hs.ship.HsHangar.variantExists(it) }
            .map { if (xyz.znix.xftl.hs.ship.HsShipUnlocks.checkCustomShipVictory(it) != -1) it else null }
            else cell.family.ships.map { state.profile.getLayoutVictory(it) }
        icon(cell.x+117,"img/achievements/S_V_"+(if(wins.any { it!=null }) "on" else "off")+".png","victory")
        // Two-layout families centre their two dots (+6 rather than +1).
        val dotX=cell.x+117+(if(wins.size<3) 6 else 1)
        if(wins.any { it!=null }) wins.forEachIndexed { i,win -> state.getImg("img/achievements/S_V_dot_"+(if(win!=null) "on" else "off")+".png").draw(dotX+i*10,y+147) }
        val customQuest = cell.custom && cell.family.ships.any { xyz.znix.xftl.hs.ship.HsShipUnlocks.unlocksOf(it).any { u -> u.type == xyz.znix.xftl.hs.ship.HsShipUnlocks.Type.EVENT } }
        if(cell.family.hasQuest || customQuest) {
            val earned=if (cell.custom) cell.family.ships.any { xyz.znix.xftl.hs.ship.HsShipUnlocks.checkCustomShipUnlockQuest(it) != -1 }
                else cell.family.unlockId!=null && state.profile.getShipUnlock(cell.family)!=null
            icon(cell.x+154,"img/achievements/S_Q_"+(if(earned) "on" else "off")+".png","quest")
        }
    }

    /** UpdateFilteredAchievements: the first three selected victory types' icons replace the card's ship achievements. */
    private fun drawFilteredIcons(g: Graphics,cell: Cell,y: Int,filters: List<String>) {
        val name=cell.family.ships.first()
        filters.forEachIndexed { i,type ->
            val v=xyz.znix.xftl.hs.ship.HsVictories.achievement(type,name,
                { n -> state.catalogBlueprints.getShip(n).shipClass?.let { state.translator[it] } ?: n },{ it.get(state.translator) })
            val x=cell.x+6+i*37
            val img=state.getImgOrNull("img/"+(if(v.unlocked) v.icon else v.iconLocked)) ?: return@forEachIndexed
            img.draw(x,y+123)
            if(decorationAt(cell)=="achievement$i") {
                g.colour=Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT
                g.drawRect(x,y+123,31,31);g.drawRect(x+1,y+124,29,29)
            }
            if(v.unlocked && v.multiLayout) {
                val dotX=x+1
                for(k in 0 until 3) {
                    if(!cell.custom || xyz.znix.xftl.hs.ship.HsHangar.button(name)?.variantExists(k)!=false)
                        state.getImg("img/achievements/S_V_dot_"+(if(v.layouts[k]>-1) "on" else "off")+".png").draw(dotX+k*10,y+147)
                }
            }
        }
    }

    /**
     * ShipSelect::Open builds an UnlockArrow after each of the first seven cards
     * and UnlockArrow::OnRender (0x00526110) draws it: a lock arrow while that
     * card's family is locked, the v (or Kestrel's 5) arrow once it is unlocked,
     * and nothing after the next family unlocks. Right arrows mirror the left art.
     */
    private fun drawArrows(g: Graphics) {
        val chain=listOf("PLAYER_SHIP_HARD","PLAYER_SHIP_CIRCLE","PLAYER_SHIP_FED","PLAYER_SHIP_ENERGY",
            "PLAYER_SHIP_MANTIS","PLAYER_SHIP_JELLY","PLAYER_SHIP_ROCK","PLAYER_SHIP_STEALTH")
        val cells=cells().associateBy { it.family.ships.first() }
        for(i in 0 until chain.size-1) {
            val cell=cells[chain[i]] ?: continue
            val next=cells[chain[i+1]] ?: continue
            val status=if(familyUnlocked(next)) 3 else if(familyUnlocked(cell)) (if(i==0) 2 else 1) else 0
            val (path,x,y)=when {
                i==3 -> Triple(when(status) { 0 -> "lock_down"; 1 -> "v_down"; else -> null },
                    cell.x+87,cell.y+160-(if(familyUnlocked(cell)) 0 else 20))
                status==2 -> Triple("5_right",cell.x+191,cell.y+66)
                status==3 -> Triple(null,0,0)
                else -> Triple(if(status==0) "lock_left" else "v_left",if(i<3) cell.x+191 else cell.x-43,cell.y+66)
            }
            val image=path?.let { state.getImgOrNull("img/customizeUI/arrow_$it.png") } ?: continue
            if(i<3 && status<2) {
                g.pushTransform();g.translate((x+image.width).toFloat(),y.toFloat());g.scale(-1f,1f);image.draw(0,0);g.popTransform()
            } else image.draw(x,y)
        }
    }

    // ---- Hyperspace pages (CustomShipSelect::OnInit buttons at (1100,119)/(1135,119), MouseClick, OnRender) ----

    private val pageButtonsActive get() = !(maxPage == 0 || (maxPage == 1 && hideFirstPage))
    private fun pageButtonAt(x: Int, y: Int): Int? {
        if (!hs || !pageButtonsActive) return null
        val img = state.getImgOrNull("img/customizeUI/button_ship_arrow_on.png") ?: return null
        if (y - pageDy !in 119 until 119 + img.height) return null
        return when (x - pageDx) { in 1100 until 1100 + img.width -> -1; in 1135 until 1135 + img.width -> 1; else -> null }
    }

    private fun switchPage(direction: Int) {
        val first = if (hideFirstPage) 1 else 0
        page = if (direction < 0) (if (page <= first) maxPage else page - 1) else (if (page >= maxPage) first else page + 1)
        hover = null
    }

    private fun drawPageButtons(g: Graphics) {
        val over = hoveredPageButton
        val y = 119 + pageDy
        for ((dir, x) in listOf(-1 to 1100 + pageDx, 1 to 1135 + pageDx)) {
            val suffix = if (!pageButtonsActive) "off" else if (over == dir) "select2" else "on"
            val img = state.getImgOrNull("img/customizeUI/button_ship_arrow_" + suffix + ".png") ?: continue
            if (dir > 0) { g.pushTransform(); g.translate((x + img.width).toFloat(), y.toFloat()); g.scale(-1f, 1f); img.draw(0, 0); g.popTransform() }
            else img.draw(x, y)
        }
        // "%d/%d" right-aligned at (1097,118), font 63.
        val text = if (hideFirstPage) "" + page + "/" + maxPage else "" + (page + 1) + "/" + (maxPage + 1)
        titleFont.drawStringFromTop(1097f + pageDx - titleFont.getWidth(text), 118f + pageDy, text, Constants.TEXT_BUTTON_TEXT)
    }

    /** CustomShipSelect::OnRender: each card's `<arrow>`s for this layout; locked art until the target unlocks. */
    private fun drawCustomArrows(g: Graphics) {
        for (cell in cells()) {
            val b = xyz.znix.xftl.hs.ship.HsHangar.button(cell.family.ships.first()) ?: continue
            if (!b.variantExists(layout)) continue
            for (arrow in b.unlockArrows) {
                if (arrow.variant != layout && arrow.variant != -1) continue
                val targetUnlocked = xyz.znix.xftl.hs.ship.HsShipUnlocks.isUnlocked(arrow.targetShip)
                val name = if (!targetUnlocked) (if (unlocked(cell)) arrow.image else arrow.lockedImage)
                    else arrow.unlockedImage.takeIf { it.isNotEmpty() } ?: continue
                val img = state.getImgOrNull("img/customizeUI/" + name) ?: continue
                // CachedImage::SetPosition from the arrow's x/y and direction: 121 high for the locked art, 161 else.
                val cardH = if (!targetUnlocked && !unlocked(cell)) 121 else 161
                val bx = cell.x + arrow.x; val by = cell.y + arrow.y
                val (x, y) = when (arrow.direction) {
                    "right" -> Pair(bx + 191, by + (cardH - img.height) / 2)
                    "down" -> Pair(bx + (191 - img.width) / 2, by + cardH)
                    "left" -> Pair(bx - img.width, by + (cardH - img.height) / 2)
                    "up" -> Pair(bx + (191 - img.width) / 2, by - img.height)
                    else -> Pair(bx, by)
                }
                // CachedImage: mirrored = horizontal flip, rotation 180 = both; mirrorY gives both, i.e. a vertical flip.
                val rot = arrow.rotation == 180f
                val sx = if (arrow.mirror != rot) -1f else 1f
                val sy = if (rot) -1f else 1f
                if (sx < 0f || sy < 0f) {
                    g.pushTransform(); g.translate(x + img.width / 2f, y + img.height / 2f); g.scale(sx, sy)
                    img.draw(-img.width / 2, -img.height / 2); g.popTransform()
                } else img.draw(x, y)
            }
        }
    }

    /** What a finger taps (TouchRouter): the tutorial's choice, a page, a tab, Accept, a ship, the space around them. */
    fun liftTarget(x: Int, y: Int): Any? {
        tutorial?.let { t -> return t.liftTarget(x - tutorialDx, y - tutorialDy)?.let { listOf("tutorial", it) } }
        pageButtonAt(x, y)?.let { return listOf("page", it) }
        tabAt(x, y)?.let { return listOf("tab", it) }
        if (touch) {
            if (overAccept(x, y)) return "accept"
            if (x < 50 || x > 974 || y < 50 || (x < 512 && y > 718) || (x > 512 && y > 668)) return "outside"
        } else if (x !in 112 until 1192 || y !in 110 until 522) return "outside"
        return cellAt(x, y) ?: "list"
    }

    fun mouseClicked(button: Int,x: Int,y: Int) {
        if(button!=Input.MOUSE_LEFT_BUTTON)return
        tutorial?.let { chooseTutorial(it.click(x-tutorialDx,y-tutorialDy));return }
        pageButtonAt(x, y)?.let { switchPage(it); return }
        if (hsUi != null) {
            val infoShown=cellAt(x,y)?.let { ship(it) } != null || (touch && cells().any { it.family==tapped })
            if (hsUi.hover(infoShown) != null) { hsUi.click(infoShown); return }
        }
        if (touch) { touchClick(x,y); return }
        tabAt(x,y)?.let { layout=it;hover=null;return }
        if(x !in 112 until 1192 || y !in 110 until 522) {close(null);return}
        val cell=cellAt(x,y) ?: return
        val blueprint=ship(cell) ?: return
        if(!available(cell) || !state.isShipUnlocked(blueprint.name)) return
        close(blueprint)
    }

    fun mouseWheelMoved(change: Int) { /* All official families fit in the native grid. */ }

    private companion object {
        /** The top left corner of the touch list's upper panel. */
        const val ORIGIN_X = 85
        const val ORIGIN_Y = 50
        const val ACCEPT_X = ORIGIN_X + 644
        const val ACCEPT_Y = ORIGIN_Y + 631
        const val ACCEPT_WIDTH = 132
        const val ACCEPT_HEIGHT = 32
    }
}
