package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/** Original progression grid, with a single layout selector for all families. */
class ShipList(private val state: SelectShipState, private val close: (LazyShipBlueprint?) -> Unit) {
    private val titleFont = state.getFont("HL2").apply { scale=3f }
    private val buttonFont = state.getFont("HL2").apply { scale=2f }
    private val labelFont = xyz.znix.xftl.rendering.NativeFonts.font(state::getFont, 62)
    private val absentFont = xyz.znix.xftl.rendering.NativeFonts.font(state::getFont, 12)
    private val order = listOf("PLAYER_SHIP_HARD","PLAYER_SHIP_CIRCLE","PLAYER_SHIP_FED","PLAYER_SHIP_ENERGY",
        "PLAYER_SHIP_STEALTH","PLAYER_SHIP_ROCK","PLAYER_SHIP_JELLY","PLAYER_SHIP_MANTIS",
        "PLAYER_SHIP_ANAEROBIC","PLAYER_SHIP_CRYSTAL")
    private var layout = 0
    private data class Cell(val family: ShipFamily,val x: Int,val y: Int)
    private fun cells() = order.mapIndexedNotNull { i,id ->
        val family=state.shipFamilies.byShipId[id] ?: return@mapIndexedNotNull null
        Cell(family,if(i<8) 136+(i%4)*205 else 976,if(i<8) 161+(i/4)*177 else 161+(i-8)*177)
    }
    private fun ship(cell: Cell) = cell.family.ships.getOrNull(layout)?.let { id -> state.catalogShips.firstOrNull { it.name==id } }
    private fun available(cell: Cell) = ship(cell)?.let { bp -> state.ships.any { it.name==bp.name } } == true
    private fun unlocked(cell: Cell) = ship(cell)?.let { state.isShipUnlocked(it.name) } == true
    private fun familyUnlocked(cell: Cell) = state.isShipUnlocked(cell.family.ships.first())
    private fun cardY(cell: Cell) = cell.y+if(familyUnlocked(cell)) 0 else 20
    private fun cellAt(x: Int,y: Int) = cells().firstOrNull {
        x in it.x until it.x+191 && y in cardY(it) until (if(familyUnlocked(it)) it.y+174 else cardY(it)+121)
    }
    private val tabWidth get() = (0..2).maxOf { buttonFont.getWidth(state.translator["typebutton_type_"+('a'+it)]) } + 4
    private val tabStride get() = tabWidth + 4
    private fun tabAt(x: Int,y: Int) = if(y in 122 until 144) (0..2).firstOrNull { x in 685+it*tabStride until 685+it*tabStride+tabWidth } else null
    private var hover: String? = null
    // ShipSelect::tutorial: shown on opening until seen, then a second page.
    private var tutorial: xyz.znix.xftl.game.NativeChoiceBox? = null
    private var tutorialPage = 0

    /** ShipSelect::Open (0x00529940): the tutorial opens unless already shown. */
    fun open() {
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
            tutorial?.text=state.translator["list_tutorial_2"]
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
        tutorial?.let { it.mouseMove(state.mousePos.x,state.mousePos.y);hover=null;return }
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
        g.colour=Colour(0f,0f,0f,0.75f);g.fillRect(0,0,container.width,container.height)
        if(tutorial!=null) Graphics.tint=Colour(0.5f,0.5f,0.5f,1f)
        state.getImg("img/customizeUI/ship_list_main_2.png").draw(112,110)
        // ShipSelect::titlePos (359,118), font 63.
        titleFont.drawStringFromTop(359f,118f,state.translator["shiplist_title"],Constants.JUMP_DISABLED_TEXT)
        drawTabFrame()
        for(i in 0..2) {
            val x=685+i*tabStride
            val over=tabAt(state.mousePos.x,state.mousePos.y)==i
            val isSelected=i==layout
            val bgColour = when {
                isSelected -> Constants.TEXT_BUTTON_SELECTED
                over -> Constants.TEXT_BUTTON_HOVER
                else -> Constants.TEXT_BUTTON_NORMAL
            }
            g.colour=bgColour
            Buttons.drawChamfer(g,x,122,tabWidth,22,3)
            labelFont.printButtonLabel(x,122,tabWidth,22,state.translator["typebutton_type_"+('a'+i)],Constants.TEXT_BUTTON_TEXT)
        }
        // No card takes the hover while the tutorial is open.
        val hovered=if(tutorial!=null) null else cellAt(state.mousePos.x,state.mousePos.y)
        cells().forEach { cell ->
            val blueprint=ship(cell)
            val open=familyUnlocked(cell);val over=hovered==cell;val y=cardY(cell)
            // ShipSelect::OnRender would give a list-selected card select2 art,
            // but Open clears that selection, so only hovering highlights.
            val highlighted=blueprint!=null && over
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
            state.getImgOrNull("img/customizeUI/miniship_"+blueprint.img+".png")?.let {
                it.draw(cell.x,y)
                if(!unlocked(cell)) it.draw(cell.x,y,Colour.black)
            }
            if(!unlocked(cell)) state.getImg("img/customizeUI/box_lock_"+(if(over) "selected" else "on")+".png").draw(cell.x+90,y+50)
        }
        drawArrows(g)
        Graphics.tint=Colour.white
        tutorial?.let { it.draw(g);return }
        hovered?.let { cell ->
            // The InfoBox follows the hovered card (its strip included); an
            // achievement icon's tooltip is drawn over it.
            ship(cell)?.let { blueprint ->
                val hidden=cell.family.ships.first()=="PLAYER_SHIP_CRYSTAL" && !state.isShipUnlocked(cell.family.ships.first())
                val title=if(hidden) state.translator["unidentified"] else blueprint.shipClass?.let { state.translator[it] } ?: blueprint.name
                // text_misc's suffix is " \1"; the translator trims its leading space.
                val suffix=" "+state.translator["shipselect_type_suffix"].replace("\\1",state.translator["type_"+('a'+layout)])
                val description=if(unlocked(cell)) blueprint.real.desc?.let { state.translator[it] } ?: "" else state.lockReasonFor(blueprint.name)
                state.hangarPresentation.drawShipListInfoBox(title+suffix,description,if(!unlocked(cell) && layout==0) 176 else 135)
            }
            val decoration=decorationAt(cell) ?: return@let
            val hint=if(decoration.startsWith("achievement")) cell.family.achievements.getOrNull(decoration.removePrefix("achievement").toInt())?.let { state.hangarPresentation.achievementHint(it) }
            else state.hangarPresentation.familyAchievementHint(cell.family,decoration)
            hint?.let { state.hangarPresentation.drawTooltip(g,it,state.mousePos.x,state.mousePos.y) }
        }
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
        cell.family.achievements.forEachIndexed { i,id ->
            val earned=state.profile.getAchievement(state.achievements[id])!=null
            icon(cell.x+6+i*37,"img/achievements/S_"+(i+1)+"_"+(if(earned) "on" else "off")+".png","achievement$i")
        }
        val wins=cell.family.ships.map { state.profile.getLayoutVictory(it) }
        icon(cell.x+117,"img/achievements/S_V_"+(if(wins.any { it!=null }) "on" else "off")+".png","victory")
        // Two-layout families centre their two dots (+6 rather than +1).
        val dotX=cell.x+117+(if(cell.family.ships.size<3) 6 else 1)
        if(wins.any { it!=null }) wins.forEachIndexed { i,win -> state.getImg("img/achievements/S_V_dot_"+(if(win!=null) "on" else "off")+".png").draw(dotX+i*10,y+147) }
        if(cell.family.hasQuest) {
            val earned=cell.family.unlockId!=null && state.profile.getShipUnlock(cell.family)!=null
            icon(cell.x+154,"img/achievements/S_Q_"+(if(earned) "on" else "off")+".png","quest")
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

    fun mouseClicked(button: Int,x: Int,y: Int) {
        if(button!=Input.MOUSE_LEFT_BUTTON)return
        tutorial?.let { chooseTutorial(it.click(x,y));return }
        tabAt(x,y)?.let { layout=it;hover=null;return }
        if(x !in 112 until 1192 || y !in 110 until 522) {close(null);return}
        val cell=cellAt(x,y) ?: return
        val blueprint=ship(cell) ?: return
        if(!available(cell) || !state.isShipUnlocked(blueprint.name)) return
        close(blueprint)
    }

    fun mouseWheelMoved(change: Int) { /* All official families fit in the native grid. */ }
}
