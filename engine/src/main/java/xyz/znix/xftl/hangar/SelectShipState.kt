package xyz.znix.xftl.hangar

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.JDOMException
import ftl.browser.BrowserSaxBuilder
import xyz.znix.xftl.*
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.game.RunConfiguration
import xyz.znix.xftl.Constants
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.WindowRenderer
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.ResourceContext
import xyz.znix.xftl.ui.*
import java.io.IOException

/**
 * The ship hangar. [advancedEdition] is the toggle's position, which is a choice for the next run: the game content
 * (events, blueprints, sounds) only follows it when a run starts, so flipping the toggle never rebuilds it. A state
 * made by a toggle click takes the pictures, fonts and texts of the one it replaces ([previous]) instead of loading
 * them again.
 */
class SelectShipState(
    val datafile: Datafile,
    private val main: MainGame,
    val advancedEdition: Boolean = ftl.browser.BrowserFiles.enableAE,
    previous: SelectShipState? = null
) : MainGame.GameState() {
    companion object {
        // ShipBuilder's family order table (0x0087b660: ids 0,3,4,7,2,5,6,1,9,8),
        // which the arrows cycle through and the ship list lays out.
        private val NATIVE_ORDER = listOf("PLAYER_SHIP_HARD", "PLAYER_SHIP_CIRCLE", "PLAYER_SHIP_FED",
            "PLAYER_SHIP_ENERGY", "PLAYER_SHIP_MANTIS", "PLAYER_SHIP_JELLY", "PLAYER_SHIP_ROCK",
            "PLAYER_SHIP_STEALTH", "PLAYER_SHIP_ANAEROBIC", "PLAYER_SHIP_CRYSTAL")
    }
    private val resourceContext: ResourceContext = previous?.resourceContext ?: ResourceContext()

    // The state that replaces this one on a toggle click takes the resources over, and frees them in the end.
    private var ownsResources = true

    val mousePos = Point(0, 0)
    val shipOffset = Point(0, 0)

    private val shipSelectorPos = ConstPoint(20, 50)

    // This is the size of the game window, not the full screen - but 'window'
    // also refers to in-game windows.
    val screenSize = Point(0, 0)

    val blueprints: BlueprintManager = main.getHangarBlueprints(advancedEdition)
    val translator: Translator = previous?.translator ?: Translator(datafile, "en")
    val animations: Animations = previous?.animations ?: Animations(datafile)

    val roomImageMeta: RoomImageMeta = previous?.roomImageMeta ?: RoomImageMeta.loadFromResource()

    val windowRenderer: WindowRenderer

    val shipFamilies: ShipFamily.FamilyTable
    val hangarFamilies get() = shipFamilies.families.sortedBy { NATIVE_ORDER.indexOf(it.ships.first()) }
    val ships: List<LazyShipBlueprint>
    // Native List keeps AE-only silhouettes visible even with default content.
    val catalogBlueprints = main.getHangarBlueprints(true)
    val catalogShips get() = catalogBlueprints.blueprints.values.filterIsInstance<LazyShipBlueprint>()

    private val current: EditableShip get() = editor.ship
    private val currentBlueprint: ShipBlueprint get() = blueprints.getShip(current.baseBlueprint)
    val blueprint get() = currentBlueprint
    val previewEditor get() = editor
    val profile get() = main.profile
    val sounds get() = main.gameContent.sounds
    val achievements get() = main.gameContent.achievements
    private lateinit var presentation: HangarPresentation
    val hangarPresentation get() = presentation
    val hoveredDifficulty get() = startGameButton.hoveredDifficulty
    val selectedDifficulty get() = startGameButton.selected
    val isShipListVisible get() = shipListVisible
    val isShipListTutorialOpen get() = shipListVisible && shipList.isTutorialOpen

    var isShipEdited = false
        private set

    private val images: HashMap<String, Image> = previous?.images ?: HashMap<String, Image>()
    private val fonts: HashMap<String, SILFontLoader> = previous?.fonts ?: HashMap<String, SILFontLoader>()

    private val editFileControls: EditFileControls

    private lateinit var editor: ShipEditor

    private val startGameButton: StartGameButton

    private val uiProvider: UIProvider = ShipSelectUIProvider()
    private val shipSelector: WidgetContainer

    private val shipList: ShipList
    private var shipListVisible = false
    private var musicStarted = false
    var roomsVisible = true
        private set
    val selectedShipId get() = currentBlueprint.name
    val isSelectedUnlocked get() = isShipUnlocked(selectedShipId)
    fun isShipUnlocked(ship: String) = main.profile.isShipUnlocked(ship)
    val lockReason: String get() = lockReasonFor(selectedShipId)
    fun lockReasonFor(ship: String): String {
        val family = shipFamilies.byShipId[ship] ?: return "Unavailable ship"
        fun title(id: String) = catalogBlueprints.getShip(id).shipClass?.let { translator[it] } ?: id
        fun format(key: String, first: String, second: String = "") = translator[key].replace("\\1",first).replace("\\2",second)
        return when (family.ships.indexOf(ship)) {
            1, 2 -> catalogBlueprints.getShip(ship).unlockHint?.let { translator[it] } ?: ""
            else -> when (ship) {
                // ShipSelect::MouseMove uses the blueprint's own unlock text, with no alternative.
                xyz.znix.xftl.game.ShipProgression.ENGI -> catalogBlueprints.getShip(ship).unlockHint?.let { translator[it] } ?: ""
                xyz.znix.xftl.game.ShipProgression.LANIUS -> catalogBlueprints.getShip(ship).unlockHint?.let { translator[it] } ?: ""
                xyz.znix.xftl.game.ShipProgression.CRYSTAL -> (catalogBlueprints.getShip(ship).unlockHint?.let { translator[it] } ?: "")+"\n"+format("alt_unlock",translator["alt_unlock_crystal"])
                else -> {
                    val preceding=mapOf("PLAYER_SHIP_FED" to "PLAYER_SHIP_CIRCLE","PLAYER_SHIP_ENERGY" to "PLAYER_SHIP_FED",
                        "PLAYER_SHIP_MANTIS" to "PLAYER_SHIP_ENERGY","PLAYER_SHIP_JELLY" to "PLAYER_SHIP_MANTIS",
                        "PLAYER_SHIP_ROCK" to "PLAYER_SHIP_JELLY","PLAYER_SHIP_STEALTH" to "PLAYER_SHIP_ROCK")[ship]
                    (catalogBlueprints.getShip(ship).unlockHint?.let { translator[it] } ?: "") + (preceding?.let {
                        "\n"+format("alt_unlock",format(if(isShipUnlocked(it)) "alt_unlock_victory" else "alt_unlock_victory_locked",title(it)))
                    } ?: "")
                }
            }
        }
    }

    val font = getFont("c&c")
    val fontHL2 = getFont("HL2")

    val missingImage = getImgOrNull("img/nullResource.png") ?: error("Missing nullResource.png")

    /** The hangar backdrop; vanilla art that is exactly the game's size, so
     *  it can be drawn 1:1 behind the ship-selection UI. */
    private val hangarBackground = getImg("img/customizeUI/custom_background.png")

    init {
        startGameButton = StartGameButton(this)
        editFileControls = EditFileControls(this)

        val background = getImg("img/window_base.png")
        val outline = getImg("img/window_outline.png")
        val mask = getImg("img/window_mask.png")
        windowRenderer = WindowRenderer(background, outline, mask)

        previous?.ownsResources = false

        // TODO load this via the usual asset system, so mods can override it
        // TODO de-duplicate with InGameState
        val builder = com.pocketwormhole.android.SafeXML.builder()
        try {
            shipFamilies = previous?.shipFamilies ?: ShipFamily.FamilyTable(builder.build(XftlResources.open("assets/data/xftl_ships.xml")))
        } catch (e: IOException) {
            throw RuntimeException("Failed to load ship data", e);
        } catch (e: JDOMException) {
            throw RuntimeException("Failed to parse ship data", e);
        }
        val familyNames = shipFamilies.families.map { it.ships[0] }

        ships = blueprints.blueprints.values
            .asSequence()
            .filterIsInstance(LazyShipBlueprint::class.java)
            .filter { it.name.startsWith("PLAYER_SHIP_") && it.name != "PLAYER_SHIP_EASY" && it.name != "PLAYER_SHIP_TUTORIAL" }
            .sortedBy { it.name }
            .sortedBy { ship -> familyNames.indexOfFirst { ship.name.startsWith(it) } }
            .toList()

        shipSelector = SpecDeserialiser(uiProvider).load("ship_list_abc").mainWidget

        shipSelector.addButtonListener("random") { selectShip(ships.filter { isShipUnlocked(it.name) }.random(ftl.browser.BrowserRandom).real) }

        shipSelector.addButtonListener("type_a") { selectShipVariant(0) }
        shipSelector.addButtonListener("type_b") { selectShipVariant(1) }
        shipSelector.addButtonListener("type_c") { selectShipVariant(2) }

        shipSelector.addButtonListener("list") { openShipList() }

        shipSelector.allWidgets.filterIsInstance<ImageButton>().forEachIndexed { index, button ->
            button.clickListener = { selectNextShip(if (index == 0) -1 else 1) }
        }
        val roomButton = shipSelector.allWidgets.filterIsInstance<UIKitButton>().last()
        roomButton.clickListener = {
            roomsVisible = !roomsVisible
            roomButton.children.filterIsInstance<Label>().firstOrNull()?.text = if (roomsVisible) translator["ship_hiderooms_button"] else "SHOW ROOMS"
        }

        shipList = ShipList(this) {
            shipListVisible = false
            if (it != null) {
                selectShip(it.real)
            }
        }

        presentation = HangarPresentation(this)
        selectShip(ships.first { it.name == shipFamilies.families.first().ships[0] }.real)
    }

    override fun init(container: GameContainer) {
        super.init(container)
        screenSize.x = container.width
        screenSize.y = container.height
    }

    override fun shutdown() {
        if (ownsResources) resourceContext.freeAll()
    }

    override fun render(container: GameContainer, g: Graphics) {
        g.clear(Colour.darkGray)

        // The hangar art (1280x720, matching the game area exactly).
        hangarBackground.draw(0f, 0f)
        presentation.drawBackgroundAnimations()

        screenSize.x = container.width
        screenSize.y = container.height

        startGameButton.draw(g)

        // File-based ship editing is outside the browser game scope.

        g.pushTransform()
        // Original ShipManager applies the layout's tile offset before the
        // hangar's fixed (365,30) translation. Hull padding is not a centering box.
        // ShipBuilder::OnRender translates by (365,30) plus the layout's shift.
        shipOffset.x = 365 + currentBlueprint.layoutShift.x + currentBlueprint.roomOffset.x * Constants.ROOM_SIZE
        shipOffset.y = 30 + currentBlueprint.layoutShift.y + currentBlueprint.roomOffset.y * Constants.ROOM_SIZE
        g.translate(shipOffset.x.f, shipOffset.y.f)
        // The hangar shows a real ShipManager: without powered sensors only crewed rooms
        // (and those a Slug's doors lead to) are visible, and without a powered oxygen
        // system its rooms hold none, like the rooms Lanius crew stand in.
        val systems = current.rooms.mapNotNull { it.system }
        val sensors = systems.any { it.type == "sensors" && it.startingPower > 0 }
        val oxygen = systems.any { it.type == "oxygen" && it.startingPower > 0 }
        val seen = presentation.crewRooms().toHashSet()
        for (slugRoom in presentation.slugRooms()) {
            for (door in current.doors.filter { it.isRoomNeighbour(slugRoom) })
                door.findNeighbourRoom(current, slugRoom)?.let { seen.add(it) }
        }
        val drained = presentation.anaerobicRooms()
        current.draw(g, this, true, { sensors || it in seen }, { !oxygen || it in drained })
        presentation.drawThrusters()
        presentation.drawShipCrew()
        editor.editorWidth = container.width - shipOffset.x
        editor.editorHeight = container.height - shipOffset.y
        g.popTransform()
        presentation.draw(g)

        if (shipListVisible) {
            shipList.draw(container, g)
        }
    }

    override fun update(container: GameContainer, delta: Float) {
        screenSize.x = container.width
        screenSize.y = container.height
        val input = container.input
        mousePos.x = input.mouseX
        mousePos.y = input.mouseY

        shipSelector.updateMouse(mousePos.x - shipSelectorPos.x, mousePos.y - shipSelectorPos.y)
        presentation.update(delta)
        startGameButton.updateHover(!shipListVisible && !presentation.isEditing && !presentation.isCustomizing)
        if(shipListVisible)shipList.update()
        if(!musicStarted) {
            musicStarted=true
            val title=sounds.getTrack("title")
            if((sounds as? xyz.znix.xftl.game.RealSoundManager)?.currentMusic !== title) sounds.switchMusicList(listOf(title))
        }
        (sounds as? xyz.znix.xftl.game.RealSoundManager)?.let { it.updateLoopedSounds(false); it.updateMusic(delta) }
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        mousePos.x = x
        mousePos.y = y
        if (shipListVisible) {
            shipList.mouseClicked(button, x, y)
            return
        }

        if (presentation.click(button,x,y)) return

        // Block editor interactions if we're only inspecting a ship
        if (isShipEdited) {
            editor.mouseClicked(button, x - shipOffset.x, y - shipOffset.y, clickCount)
        }

        startGameButton.mouseClicked(button, x, y)

        // No hidden file-editor hit targets.

    }

    override fun mousePressed(button: Int, x: Int, y: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mousePressed(button, x - shipOffset.x, y - shipOffset.y)
        }
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mouseReleased(button, x - shipOffset.x, y - shipOffset.y)
        }
    }

    override fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mouseDragged(oldX - shipOffset.x, oldY - shipOffset.y, newX - shipOffset.x, newY - shipOffset.y)
        }
    }

    override fun mouseMoved(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mouseMoved(newX - shipOffset.x, newY - shipOffset.y)
        }
    }

    override fun mouseWheelMoved(change: Int) {
        if (shipListVisible) {
            shipList.mouseWheelMoved(change)
            return
        }

        if (isShipEdited) {
            editor.mouseWheelMoved(change)
        }
    }

    override fun keyPressed(key: Int, c: Char) {
        if (shipListVisible) {
            if (key == Input.KEY_ESCAPE)
                shipListVisible = false
            else shipList.keyPressed(c)
            return
        }

        val input=main.gameContainer.input
        val shortcut=listOf(Input.KEY_LCTRL,Input.KEY_RCTRL,Input.KEY_LALT,Input.KEY_RALT,Input.KEY_LSUPER,Input.KEY_RSUPER).any { input.isKeyDown(it) }
        if (presentation.key(key,if(shortcut) '\u0000' else c)) return
        if (key == Input.KEY_ESCAPE) main.switchToMainMenu()
    }

    fun getImg(path: String): Image {
        getImgOrNull(path)?.let { return it }

        println("[WARN] Invalid path for image: '$path'")
        images[path] = missingImage // Don't repeat the warning for each getImg call
        return missingImage
    }

    fun getImgOrNull(path: String): Image? {
        images[path]?.let { return it }

        val file = datafile.getOrNull(path) ?: return null
        val img = datafile.readImage(resourceContext, file)
        images[path] = img
        return img
    }

    fun getFont(name: String): SILFontLoader {
        // Always return a copy of the font, so our instance doesn't get broken
        // when a consumer sets their instance's scale property or anything
        // like that.
        fonts[name]?.let { return SILFontLoader(it) }

        val font = SILFontLoader(resourceContext, datafile, datafile["fonts/$name.font"])
        fonts[name] = font
        return SILFontLoader(font)
    }

    fun startGame(selected: Difficulty) {
        if (!isSelectedUnlocked) return
        presentation.commit()
        val editedShip = if (isShipEdited) editor.ship else null
        main.startNewGame(RunConfiguration(currentBlueprint.name, selected, advancedEdition,
            presentation.shipName, presentation.crew.map { it.copy() }), editedShip)
    }

    fun restoreSelection(shipId: String, difficulty: Difficulty) {
        val available = ships.firstOrNull { it.name == shipId }
            ?: shipFamilies.byShipId[shipId]?.ships?.firstOrNull()?.let { id -> ships.firstOrNull { it.name == id } }
        if (available != null) selectShip(available.real)
        startGameButton.selected = difficulty
    }

    fun selectNextShip(direction: Int) {
        nextShip(direction)?.let { selectShip(it.real) }
    }

    /** ShipBuilder::GetNextShipId/GetPrevShipId: the next family unlocked for the current layout type. */
    private fun nextShip(direction: Int): LazyShipBlueprint? {
        val families = hangarFamilies
        val family = shipFamilies.byShipId[currentBlueprint.name]
        val index = families.indexOf(family).coerceAtLeast(0)
        val layout = family?.ships?.indexOf(currentBlueprint.name) ?: 0
        for (step in 1..families.size) {
            val next = families[(index + direction * step + families.size) % families.size]
            val id = next.ships.getOrNull(layout) ?: continue
            val ship = ships.firstOrNull { it.name == id && isShipUnlocked(id) } ?: continue
            return if (ship.name != selectedShipId) ship else null
        }
        return null
    }

    /** The native arrows are inactive when cycling would keep the current ship. */
    fun canCycleShips(direction: Int) = nextShip(direction) != null

    /** ShipBuilder::OnLoop enables Random Ship only with more than one unlocked family. */
    val unlockedFamilyCount get() = hangarFamilies.count { family -> family.ships.firstOrNull()?.let { isShipUnlocked(it) } == true }

    fun selectRandomShip(random: kotlin.random.Random) { selectShip(ships.filter { isShipUnlocked(it.name) }.random(random).real) }
    fun setRoomsVisible(visible: Boolean) { roomsVisible=visible }
    fun openShipList() { presentation.commit(); shipListVisible=true; shipList.open() }
    fun back() { main.switchToMainMenu() }
    fun changeMode(enabled: Boolean) {
        if(enabled==advancedEdition) return
        presentation.commit()
        val selected=selectedShipId; val name=presentation.shipName;val crew=presentation.crew.map { it.copy() };val visible=roomsVisible
        main.selectHangarMode(enabled,selected,startGameButton.selected,this)
        val next=main.currentState as SelectShipState
        if(next.selectedShipId==selected) next.presentation.restore(name,crew,visible)
    }
    fun startEditingShip() {
        isShipEdited = true
    }

    fun stopEditingShip() {
        isShipEdited = false

        // Re-create the editor to throw away the changes
        editor = ShipEditor(this, EditableShip.fromBlueprint(currentBlueprint))
    }

    private fun selectShip(blueprint: ShipBlueprint) {
        val preview = EditableShip.fromBlueprint(blueprint)
        for (room in preview.rooms) {
            val system = room.system ?: continue
            if (system.startingPower == 0 || !blueprints.blueprints.containsKey(system.type)) room.system = null
        }
        editor = ShipEditor(this, preview)
        presentation.select()

        // Update the A/B/C variant buttons
        val family = shipFamilies.byShipId[blueprint.name]
        val typeId = family?.ships?.indexOf(blueprint.name) ?: 0

        fun updateTypeButton(id: String, idx: Int) {
            val button = shipSelector.byId[id] as UIKitButton

            // A family may only list variants that exist as blueprints - e.g.
            // there's no Crystal or Lanius type C (GitHub issue #108).
            val variantExists = family?.ships?.getOrNull(idx)?.let { shipId ->
                ships.any { it.name == shipId }
            } == true

            if (idx == typeId) {
                // Disable to prevent clicking, but still show as selected.
                button.forceSelected = true
                button.disabled = true
            } else if (family == null || !variantExists) {
                button.forceSelected = false
                button.disabled = true
            } else {
                button.forceSelected = false
                button.disabled = false
            }
        }

        updateTypeButton("type_a", 0)
        updateTypeButton("type_b", 1)
        updateTypeButton("type_c", 2)
    }

    /**
     * Switch between the A/B/C variants of the current ship.
     */
    fun selectShipVariant(index: Int) {
        val family = shipFamilies.byShipId[current.baseBlueprint] ?: return
        val shipId = family.ships.getOrNull(index) ?: return

        val blueprint = ships.firstOrNull { it.name == shipId } ?: return
        selectShip(blueprint.real)
    }

    private inner class ShipSelectUIProvider : UIProvider {
        override fun getFont(name: String): SILFontLoader {
            return this@SelectShipState.getFont(name)
        }

        override fun getImg(path: String): Image {
            return this@SelectShipState.getImg(path)
        }

        override fun translate(key: String): String? {
            return translator.translations[key]
        }

        override fun getDebugOutlineColour(widget: Widget): Colour? {
            return null
        }

        override fun getWindowRenderer(): WindowRenderer {
            return windowRenderer
        }
    }
}
