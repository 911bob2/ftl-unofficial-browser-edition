package xyz.znix.xftl.game

import org.jdom2.Element
import org.newdawn.slick.geom.Rectangle
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Choice
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sector.EventText
import xyz.znix.xftl.sector.EventStatus
import xyz.znix.xftl.sector.EventSystemUpgrade
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class EventChoiceHotkeyMode(val id: Int, val textKey: String) {
    DISABLED(0, "event_choice_disable_hotkeys"),
    ENABLED(1, "event_choice_no_delay"),
    BRIEF_DELAY(2, "event_choice_brief_delay");

    companion object {
        fun fromId(id: Int?) = entries.firstOrNull { it.id == id } ?: ENABLED
    }
}

class DialogueWindow private constructor(val game: InGameState, val playerShip: Ship, val close: () -> Unit) :
    Window(), xyz.znix.xftl.hs.events.HsFlow.Host, xyz.znix.xftl.hs.events.HsEventsLua.ChoiceBoxText {

    /** Hyperspace Lua: ChoiceBox.mainText, the box's text as shown. */
    override var mainText: String
        get() = luaMainText ?: ((if (textlessBox) "" else currentEvent?.text ?: "") + extraText)
        set(v) { luaMainText = v }

    /** The never-shown box of an event without text is being made: its text is empty, not the parent box's (oracle ps-w2-ops). */
    private var textlessBox = false

    /** Hyperspace Lua: ChoiceBox.mainText as a script wrote it, replacing the text drawn; reset with each box. */
    private var luaMainText: String? = null

    /** Hyperspace Lua: ChoiceBox:GetChoices() / .choices, one entry per option as the box lists them. */
    override val choiceEntries: List<xyz.znix.xftl.hs.events.HsEventsLua.ChoiceEntry>
        get() = if (::options.isInitialized) options.indices.map { OptionEntry(it) } else emptyList()

    override val potentialChoice: Int get() = hoveredOption ?: -1

    private inner class OptionEntry(val index: Int) : xyz.znix.xftl.hs.events.HsEventsLua.ChoiceEntry {
        override val created: xyz.znix.xftl.sector.CreatedEvent? get() = options.getOrNull(index)?.created
        override val hidden: Boolean get() = options.getOrNull(index)?.choice?.hidden == true
        override var type: Int
            get() = hsTypes?.getOrNull(index) ?: 0
            set(v) {
                val types = hsTypes ?: IntArray(options.size).also { hsTypes = it }
                if (index < types.size) types[index] = v
            }
        override var text: String
            get() = options.getOrNull(index)?.choiceText ?: ""
            set(v) { options.getOrNull(index)?.choiceTextOverride = v }
    }

    /** Hyperspace: the choice box type of each option (0 normal, 1 unavailable, 2 blue); null in vanilla. */
    private var hsTypes: IntArray? = null

    /** Hyperspace: while > 0 an event's HS steps are still running, and closing the window waits for them. */
    private var hsDepth = 0

    /** Hyperspace: this window is showing an event (its hooks and on_game_event callbacks are running). */
    val hsShowing: Boolean get() = hsDepth > 0

    init {
        // WorldManager +0x12a0 (the crew-kill seed) is cleared only where a new event sequence starts: CreateLocation's
        // arrival event (1.6.14 0x6183a0, write at 0x61880d; 1.6.9 write at 0x5f30c6) and OnLoop's ship endings, CREW_STUCK and jump (1.6.14 0x61b930, 1.6.9 0x5f6a4a / 0x5f6eca / 0x5f7338), not
        // for the events a choice leads to. Each of those opens a window; a choice's event stays in this one, so a chain
        // of boxes keeps its seed and its draws.
        game.eventManager.resetCrewKillSeed()
    }

    // The touch layout's rows are taller, and the frame grows to hold many of them.
    override val size: IPoint
        get() = ConstPoint(
            602,
            if (!TouchMode.enabled) 377
            else max(377, 49 * (if (syntheticEvents.isNotEmpty() || !::options.isInitialized) 1 else options.size) + 88)
        )

    // Touch ergonomics: bigger option text/targets. 1.3x (783x490) fits
    // the canvas; nudged left+up so it sits a little clearer of the touch
    // HUD (top-bar buttons to the right, scaled systems strip below).
    // Both values are by-eye tuning points.
    override val renderScale = if (PlatformSpecific.INSTANCE.isTouchUi) 1.3f else 1f
    // Desktop: ChoiceBox frames its text at (334,147) (NativeChoiceBox), 150px
    // further left while an enemy ship is present.
    override val windowCentreOffset: ConstPoint
        get() = if (TouchMode.enabled) ConstPoint(
            // The frame's left edge in the HUD block is 206, 150 px further left while an enemy ship is present; its top is 191.
            (if (game.enemy != null) 56 else 206) - (TouchMode.HUD_W - size.x) / 2,
            191 - (TouchMode.HUD_H - size.y) / 2
        ) else ConstPoint(
            if (PlatformSpecific.INSTANCE.isTouchUi) -40 else if (game.enemy != null) -155 else -5,
            if (PlatformSpecific.INSTANCE.isTouchUi) -60 else -24
        )

    override val desktopCentreOffset: IPoint get() = ConstPoint(if (game.enemy != null) -155 else -5, -24)

    private val resourceNumFont = game.getFont("JustinFont10")
    private val font = game.getFont("JustinFont11Bold")

    /**
     * ChoiceBox::OnRender prints the text and the choices in the box's font size ([EventFontSize]),
     * placing lines by that font's line height in the current language; 12 is [font] itself.
     */
    private val textFonts = HashMap<Int, SILFontLoader>()
    private var textSize = 12
    private val textFont: SILFontLoader
        get() = if (textSize == 12) font else textFonts.getOrPut(textSize) {
            xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, textSize).font
        }

    /** The text font's English baseline (its language copy moves glyphs to its own) and its line height. */
    private val textBaseline get() = xyz.znix.xftl.rendering.NativeFonts.baseSpec(textSize).baseline
    private val textLineHeight get() = xyz.znix.xftl.rendering.NativeFonts.spec(textSize).lineHeight

    // Layout offsets from the font (ChoiceBox::OnRender): a choice's baseline sits 10px plus the baseline below its
    // top, the next choice 12px below its last line, the choices 30px (gap_size) below the text. With font 12
    // these are the values the layout was measured with.
    private val fontHeight get() = textBaseline - 1
    private val optionTopOffset get() = textBaseline + 3
    private val optionBottomMargin get() = textLineHeight - textBaseline + 9

    private val augmentStringWidth = font.getWidth(game.translator["augment"])

    private val shipUnlockSound = game.sounds.getSample("unlock")

    /**
     * The currently-shown event. This is null if there's only [syntheticEvents]
     * remaining.
     */
    private var currentEvent: EvaluatedEvent? = null
    private var secretSectorPending = false

    private lateinit var options: List<EvaluatedEvent>

    private val optionBoundingBoxes = ArrayList<Rectangle>()
    private var boxesBuiltAtX = Int.MIN_VALUE
    private var hoveredOption: Int? = null

    /** Touch layout: a finger came down on this box and has not lifted. A row is only hovered then, and chosen by the lift. */
    private var touchFingerDown = false
    // Native ChoiceBox SetChoices resets openTime only when reopening a closed
    // box. Choices in the same open event chain retain their elapsed time.
    var openTime = 0f
        private set
    private val choiceMode get() = game.mainGame?.profile?.eventChoiceHotkeys ?: EventChoiceHotkeyMode.ENABLED
    val choiceInputReady get() = choiceMode != EventChoiceHotkeyMode.BRIEF_DELAY || openTime >= .3f
    val diagnosticChoiceBounds: String get() = optionBoundingBoxes.joinToString(",", "[", "]") {
        "{\"x\":${it.x},\"y\":${it.y},\"width\":${it.width},\"height\":${it.height}}"
    }
    fun choiceCentre(index: Int): IPoint? = optionBoundingBoxes.getOrNull(index)?.let {
        ConstPoint((it.x + it.width / 2).toInt(), (it.y + it.height / 2).toInt())
    }

    fun updateChoiceClock(dt: Float) {
        // TeaVM uses JS numbers for float arithmetic. Retain the original
        // float32 timer's rounding at the input threshold.
        if (dt.isFinite() && dt > 0f) openTime = min(.3f, float32(openTime + dt))
    }
    private fun float32(value: Float) = java.lang.Float.intBitsToFloat(java.lang.Float.floatToIntBits(value))

    /**
     * The list of [SyntheticEvent]s that should be shown, one after the other.
     *
     * Adding a synthetic event will cause it to be shown in front of the
     * current event, even if that's already visible.
     */
    private val syntheticEvents = ArrayList<SyntheticEvent>()

    /**
     * Hyperspace: the choice whose next event waits for the synthetic events its resources raised. The original
     * shows AUGMENT_FULL as an event box before it builds the next one (the oracle's box and resource order).
     */
    private var hsPendingChoice: EvaluatedEvent? = null

    private val textX get() = position.x + 18

    private var extraText = ""

    private val continueEvent = EvaluatedEvent(null, game, null, null)

    /**
     * Show an event the game names by its template (a list pick the caller
     * made, a console command, a quest): it is created now, with the global
     * stream, like CreateEvent does for the original.
     */
    constructor(game: InGameState, playerShip: Ship, startingEvent: Event?, seed: Int, close: () -> Unit)
            : this(game, playerShip, startingEvent?.let { game.eventCreator.createFrom(it, game.eventCreator.currentLevel()) }, close)

    /**
     * Show an event that was already created, such as the one the sector
     * generation put on the beacon. An event that only names its template
     * (older saves) is created first.
     */
    constructor(game: InGameState, playerShip: Ship, startingEvent: CreatedEvent?, close: () -> Unit)
            : this(game, playerShip, startingEvent, false, close)

    /** Hyperspace: CreateLocation of a revisited beacon; the window closes at once unless a revisit event loads. */
    constructor(game: InGameState, playerShip: Ship, revisited: xyz.znix.xftl.sector.Beacon, close: () -> Unit)
            : this(game, playerShip, close) {
        options = listOf(continueEvent)
        hsDepth++
        try {
            xyz.znix.xftl.hs.events.HsEventsLua.gameEvent(revisited.createdEvent, false)
            xyz.znix.xftl.hs.events.HsFlow.beforeCreateLocation(revisited.createdEvent)
            xyz.znix.xftl.hs.events.HsFlow.afterCreateLocation(game, this, revisited, revisited.createdEvent, true)
        } finally {
            hsDepth--
        }
        checkClosed()
    }

    /**
     * [hsArrival]: the beacon's own event shown on arrival (WorldManager::CreateLocation) rather than an event
     * loaded later (UpdateLocation); only Hyperspace tells them apart.
     */
    constructor(game: InGameState, playerShip: Ship, startingEvent: CreatedEvent?, hsArrival: Boolean, close: () -> Unit)
            : this(game, playerShip, close) {

        // Starting event can be null to get the window open before adding
        // a synthetic event.
        if (startingEvent != null) {
            val created = if (startingEvent.eager) startingEvent
            else game.eventCreator.createFrom(startingEvent.template, game.eventCreator.currentLevel())
            if (created != null) {
                if (game.eventManager.hs != null) hsShow(EvaluatedEvent(created, game, null, null), hsArrival)
                else loadEvent(EvaluatedEvent(created, game, null, null))
            } else {
                options = listOf(continueEvent)
                close()
            }
        } else {
            // We have to set this for serialisation, and although it should
            // never be observed, it's still worth adding the continue event
            // to avoid the player getting stuck if something happens.
            options = listOf(continueEvent)
        }
    }

    /**
     * Hyperspace: show an event the way the original's WorldManager does, with HS's hooks around the original's
     * part ([loadEvent]): CreateLocation for the beacon's event on arrival, UpdateLocation otherwise.
     */
    private fun hsShow(event: EvaluatedEvent, arrival: Boolean) {
        val created = event.created ?: return loadEvent(event)
        val flow = xyz.znix.xftl.hs.events.HsFlow
        hsDepth++
        try {
            // LuaLibScript.cpp: script.on_game_event callbacks before CreateLocation / UpdateLocation.
            xyz.znix.xftl.hs.events.HsEventsLua.gameEvent(created, false)
            if (arrival) {
                flow.beforeCreateLocation(created)
                loadEvent(event)
                flow.afterCreateLocation(game, this, game.currentBeacon, created, false)
            } else {
                flow.beforeUpdateLocation(game, created)
                loadEvent(event)
                flow.afterUpdateLocation(game, this, created)
            }
        } finally {
            hsDepth--
        }
        if (hsDepth == 0) checkClosed()
    }

    /** HsFlow.Host: UpdateLocation of an event HS loads (loadEvent, loadEventList, queued, jump, death events). */
    override fun hsUpdateLocation(event: CreatedEvent) {
        hsShow(EvaluatedEvent(event, game, null, null), false)
    }

    private fun loadEvent(event: EvaluatedEvent) {
        // Hyperspace: WorldManager::CreateChoiceBox resets the scroll
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsScrollingChoiceBox.reset()
        extraText = ""
        luaMainText = null

        // Vanilla WorldManager::CreateStore (1.6.14 0x613010) precedes the ship's reseed.
        // HS 1b05a07 CustomStore.cpp rewrites CreateLocation/UpdateLocation (priority 9999):
        // CreateShip precedes CreateStore. Keep each version's order, including vanilla stores under HS.
        if (event.event.isStore) {
            game.currentBeacon.hasStore = true
            if (!xyz.znix.xftl.hs.HS.active) game.currentBeacon.createStore(game, event.created)
        }

        game.loadEventShip(event.created!!)
        if (xyz.znix.xftl.hs.HS.active && event.event.isStore)
            game.currentBeacon.createStore(game, event.created)
        event.event.environment?.let { type ->
            val beacon = game.currentBeacon
            val env = if (type == xyz.znix.xftl.sector.Beacon.EnvironmentType.PDS) xyz.znix.xftl.environment.AsbEnvironment(game, beacon, event.event.browserPdsTarget) else type.create(game, beacon)
            // Hyperspace (WorldManager::ModifyEnvironment): a hazard of a later event is added to the ones already up.
            if (xyz.znix.xftl.hs.HS.active && type != xyz.znix.xftl.sector.Beacon.EnvironmentType.NORMAL && event.event !== beacon.event) {
                beacon.getEnvironment(game)
                beacon.addEnvironment(env)
            } else beacon.debugSetEnvironment(env)
        }
        // WorldManager::ModifyEnvironment (CreateLocation 0x6183a0 / UpdateLocation, after the ship and the store):
        // SetNebula makes the nebula's clouds now, ahead of the box's own draws (once per scene).
        (game.currentBeacon.getEnvironment(game) as? xyz.znix.xftl.environment.NebulaEnvironment)?.ensureSet()
        applyStatuses(event)

        if (event.event.isStore) {
            // This does a few things: show the store on the map, enable the store button
            // and cause the store window to open when this window is closed.
            game.currentBeacon.hasStore = true
        }

        // WorldManager::CreateLocation / UpdateLocation add the event's boarders ahead of CreateChoiceBox: they are on
        // the ship while its box is open, and their draws precede the crew seed the box takes for a crew loss.
        event.created?.let { created ->
            val race = created.template.boarderRace
            if (race != null && !created.boardersAboard) {
                game.addBoarders(race, created.boarders, created.template.boarderBreach)
                created.boardersAboard = true
                if (event.resourcesEvaluated) event.resources.boardersAboard = true
            }
        }

        // If this event triggers a quest, we have to evaluate it now to find out
        // what text to display (whether it was added to the current or next
        // sector, or there's no time as the next sector is the last stand).
        val questName = event.event.questName
        if (questName != null) {
            val messageId = when (game.addQuest(questName)) {
                InGameState.QuestAddResult.CURRENT_SECTOR -> "added_quest"
                InGameState.QuestAddResult.NEXT_SECTOR -> "added_quest_sector"
                InGameState.QuestAddResult.TOO_LATE -> "no_time"
            }
            // (the original's text holds a space before the break: Hyperspace Lua reads it so)
            extraText += (if (xyz.znix.xftl.hs.HS.active) " \n\n" else "\n\n") + game.translator[messageId]
        }

        // When ships are first unlocked, they add on an extra line.
        // We also need to play the unlock sound now, so we might as well also
        // update the user's profile while we're here.
        val unlockShip = event.event.unlockShip
        if (unlockShip != null) {
            val family = game.content.shipFamilies.families.firstOrNull { it.unlockId == unlockShip }
            checkNotNull(family) { "No ship family with unlock ID: '$unlockShip'" }
            val oldStatus = game.mainGame.profile.getShipUnlock(family)
            game.mainGame.profile.unlockShip(family, game.difficulty, fromEvent = true)

            if (oldStatus == null) {
                shipUnlockSound.play()
                extraText += "\n\n" + game.translator["unlock_ship_$unlockShip"]
            }
        }

        // Hyperspace Lua: CreateChoiceBox's PRE_CREATE_CHOICEBOX (CustomAugments.cpp), before the original's part.
        if (game.eventManager.hs != null) {
            // the quest / unlock lines are part of the event's text by now: a script reads them and may rewrite them
            event.created!!.hsExtraText = extraText
            xyz.znix.xftl.hs.events.HsEventsLua.preChoiceBox(event.created!!)
            xyz.znix.xftl.hs.events.HsEventsLua.diagnosticPreBox?.invoke(event.created!!)
            extraText = event.created!!.hsExtraText ?: extraText
            event.created!!.hsExtraText = null
            xyz.znix.xftl.hs.events.HsFlow.beforeChoiceBox(game, event.created!!)
        }

        // Events that have no text are valid, usually they are the result of a choice
        // Eg, to give the player some items and close the menu
        if (event.text == null) {
            // Events that have text and give resources don't add the resources
            // until they're closed. That means the addResources call is made
            // when an option is selected. For these events that'll never happen,
            // so add the resources now.
            addResources(event)
            // CreateChoiceBox's POST_CREATE_CHOICEBOX (after its ModifyResources), then the diagnostics listener.
            if (game.eventManager.hs != null) {
                // the box CreateChoiceBox makes for it (never shown) holds the implicit Continue, not the parent box's
                // choices (oracle ps-w2-ops: REPAIR_STATION's "Ignore" event gives "Continue..." to the callback)
                if (event.created!!.choices.isEmpty()) {
                    options = listOf(continueEvent)
                    hsTypes = intArrayOf(0)
                }
                textlessBox = true
                try {
                    xyz.znix.xftl.hs.events.HsFlow.afterChoiceBox(game, event.created!!)
                    xyz.znix.xftl.hs.events.HsEventsLua.postChoiceBox(this, event.created!!)
                    xyz.znix.xftl.hs.events.HsFlow.boxListener?.invoke(game, event.created!!, null)
                } finally {
                    textlessBox = false
                }
            }

            currentEvent = null

            // Stay open if a synthetic event was just created, we'll close when
            // that's done.
            checkClosed()

            return
        }

        currentEvent = event

        // CreateChoiceBox (1.6.14 0x616680, 1.6.9 0x5f0f70): an event that kills crew draws the crew seed (WorldManager
        // +0x12a0, cleared above) and picks with it now, when its own box is made (the box shows "<name> is gone", and
        // that one dies when the box closes), not when the box is first drawn or when a parent box previews it.
        if (event.event.removedCrew.isNotEmpty()) event.resources

        if (game.eventManager.hs != null) {
            // Hyperspace: CreateChoiceBox with HS's requirement, max_group and colour rules (HsFlow.choiceBox).
            val box = xyz.znix.xftl.hs.events.HsFlow.choiceBox(game, event.created!!)
            options = box.map { EvaluatedEvent(it.choice.event, game, it.choice.template, it.choice.text) }
            // the original's part: a system the ship owns or cannot fit makes the choice unavailable
            hsTypes = box.mapIndexed { i, it -> if (systemChoiceWarning(options[i])?.second == true) 1 else it.type }.toIntArray()
            extraText += systemWarningsText()
            // An event without choices has FTL's implicit "continue" one first (LocationEvent::AddChoice appends after it);
            // a script that removed it (RemoveChoice 0) leaves only what it added.
            if (options.isNotEmpty() && event.created!!.template.choices.isEmpty() && !event.created!!.hsContinueRemoved) {
                options = listOf(continueEvent) + options
                hsTypes = intArrayOf(0) + box.map { it.type }.toIntArray()
            }
            // the box of an event without choices holds its "Continue..." choice by POST_CREATE_CHOICEBOX (oracle
            // sweep-world-y1 y-scrap: one choice, scrap 0)
            if (options.isEmpty()) {
                options = listOf(continueEvent)
                hsTypes = intArrayOf(0)
            }
            xyz.znix.xftl.hs.events.HsFlow.afterChoiceBox(game, event.created!!)
            xyz.znix.xftl.hs.events.HsEventsLua.postChoiceBox(this, event.created!!)
            xyz.znix.xftl.hs.events.HsFlow.boxListener?.invoke(game, event.created!!, box)
            if (options.isEmpty()) {
                options = listOf(continueEvent)
                hsTypes = intArrayOf(0)
            }
            optionBoundingBoxes.clear()
            return
        }

        val newOptions = ArrayList<EvaluatedEvent>()
        for (choice in event.created!!.choices)
            newOptions.add(EvaluatedEvent(choice.event, game, choice.template, choice.text))

        // CreateChoiceBox (1.6.14 0x616680, 1.6.9 0x5f0f70): a choice with a req stays only while HasEquipment is within
        // [lvl, max_lvl]. Every kept choice of a max_group is taken out of the list; per group the first one with the
        // strictly highest lvl survives, and the survivors go to the end in ascending group order. A max_group without
        // a req means nothing.
        val groups = java.util.TreeMap<Int, EvaluatedEvent>()
        val kept = ArrayList<EvaluatedEvent>()
        for (option in newOptions) {
            val choice = option.choice!!
            if (choice.req != null) {
                if (!hasRequiredEquipment(option)) continue
                val group = choice.maxGroup
                if (group != null && group != -1) {
                    val existing = groups[group]
                    if (existing == null || (existing.choice!!.minLevel ?: 1) < (choice.minLevel ?: 1)) groups[group] = option
                    continue
                }
            }
            kept.add(option)
        }
        kept.addAll(groups.values)
        newOptions.clear()
        newOptions.addAll(kept)

        options = newOptions
        extraText += systemWarningsText()

        if (options.isEmpty()) {
            options = listOf(continueEvent)
        }

        optionBoundingBoxes.clear()
    }

    override fun draw(g: Graphics) {
        // Hyperspace CHOICE_BOX render event around ChoiceBox::OnRender (fire site only)
        if (xyz.znix.xftl.hs.HS.luaActive) {
            val ren = xyz.znix.xftl.hs.render.HsRenderEvents
            val i = ren.preObj(xyz.znix.xftl.hs.lua.LuaApi.RE_CHOICE_BOX, this, xyz.znix.xftl.hs.lua.LuaApi.T_ChoiceBox)
            if (i >= 0) drawBox(g)
            ren.postObj(xyz.znix.xftl.hs.lua.LuaApi.RE_CHOICE_BOX, i)
            return
        }
        drawBox(g)
    }

    private fun drawBox(g: Graphics) {
        // Draw the frame. ChoiceBox::OnRender (0x004fc0f0): an outcome box wider than the 602px
        // frame widens it to the box's width + 25, centred on the box.
        val outcome = (syntheticEvents.firstOrNull()?.resources ?: currentEvent?.resources)?.let { hsChoiceScrap(it, currentEvent?.created) }
        val outcomeWidth = outcome?.takeIf { it.hasAnything }?.let { layoutOutcome(g, it, 0, 0, null).width } ?: 0
        if (outcomeWidth > size.x) {
            game.windowRenderer.render(position.x - 3 - (outcomeWidth - 577) / 2, position.y, outcomeWidth + 25, size.y)
        } else {
            game.windowRenderer.render(position.x, position.y, size.x, size.y)
        }

        if (!xyz.znix.xftl.hs.HS.active) {
            drawContent(g)
            return
        }
        // Hyperspace ScrollingChoiceBox: the text and choices scroll inside the frame
        xyz.znix.xftl.hs.render.HsScrollingChoiceBox.render(game, g, boxCentred, position.x, position.y, size.x, size.y) {
            drawContent(g).toFloat()
        }
        // Hyperspace event tooltips (EventTooltip.cpp: ChoiceBox::OnRender, after the box)
        if (syntheticEvents.isEmpty()) {
            val hovered = hoveredOption?.let { options.getOrNull(it) }
            val reward = hovered?.takeIf { !it.isContinue }?.let { o ->
                xyz.znix.xftl.hs.render.HsEventTooltip.reward(o.choiceResources.items, o.choiceResources.crew, o.event.removedStuff.isNotEmpty())
            }
            xyz.znix.xftl.hs.render.HsEventTooltip.render(game, g, reward, boxCentred)
        }
    }

    private val boxCentred: Boolean get() = if (TouchMode.enabled) game.enemy == null else position.x > 250

    /** ChoiceBox::OnRender's text and choices; returns lastChoice.y (the y after the last choice). */
    private fun drawContent(g: Graphics): Int {
        val fontSize = EventFontSize.clamped()
        if (fontSize != textSize) {
            textSize = fontSize
            optionBoundingBoxes.clear()
        }
        // The box moves when an enemy arrives or leaves under it; the choices must answer where they are drawn.
        if (position.x != boxesBuiltAtX) {
            boxesBuiltAtX = position.x
            optionBoundingBoxes.clear()
        }
        if (syntheticEvents.isNotEmpty()) {
            drawSyntheticEvent(g, syntheticEvents.first())
            return 0
        }

        // If syntheticEvent is empty, then currentEvent must not be null.
        val event = currentEvent!!

        var textY = drawEventOutcome(g, luaMainText ?: (event.text!! + extraText), hsChoiceScrap(event.resources, event.created))

        val rebuildBBs = optionBoundingBoxes.isEmpty()

        if (TouchMode.enabled) return drawTouchRows(g, textY - TOUCH_CHOICES_UP, rebuildBBs)

        // You can think of each option having a top and bottom Y. For text
        // events there's a spacing between the top of the space and the baseline
        // of the text (optionTopOffset), and then another offset from
        // the baseline to the bottom of the option (which is the top of the next
        // option) - optionBottomMargin.
        // When resources are involved, the top of the resource box sits exactly
        // at the option's Y top, and there's 10 pixels (RESOURCE_BOTTOM_MARGIN)
        // between the bottom of the box and the start of the next option.
        for ((i, option) in options.withIndex()) {
            val choice = option.choice
            val enabled = isConditionsSatisfied(option)
            val hsType = hsTypes?.getOrNull(i)
            val colour = optionColour(i, choice, hsType, enabled)

            val prefix = "${i + 1}. "
            var text = prefix + option.choiceText

            // Handle the 'continue' option, since it fails if
            // we try to read its resources.
            // ChoiceBox lays Continue out like any other text row.
            if (option.isContinue) {
                textY += optionTopOffset
                textY = drawText(textY, text, colour, rebuildBBs)
                textY += optionBottomMargin
                continue
            }

            // Warn the user if they'll have to fire a crewmember: CreateChoiceBox (0x616680) checks the choice event's
            // stuff.crew > 0 and that the choice's reward is not hidden, with a full crew.
            if (option.created?.crew != null && choice?.hidden != true && game.isPlayerCrewFull) {
                // event_crew_full brings its own leading space (none in Japanese and Chinese).
                text += game.translator["event_crew_full"]
            }

            // Draw the resources for this event, if appropriate
            if (option.choiceResources.hasAnything) {
                val boxSize = findResourceBoxSize(option.choiceResources)

                val textResourceMargin = 10

                // To support text wrapping, we do this in three parts:
                // 1. Figure out the maximum width of the text
                // 2. Draw the text, which tells us how wide it is
                // 3. Draw the resource box to line up with the text, or
                //    at it's maximum x position if the text is multi-line.
                // This can be tested with the ENGI_REFUGEES or
                // ZOLTAN_TRADE_HUB events (the latter with a blue option).
                // (note ENGI_REFUGEES isn't part of the final game, so
                //  you can't test it there).
                // This logic might not exactly match the base game for
                // other events, if so it should be fixed but it's not
                // likely to be a big problem.

                // Find the maximum box position
                val maxBoxRHS = position.x + size.x - 34
                val maxBoxX = maxBoxRHS - boxSize.x
                val maxTextX = maxBoxX - textResourceMargin

                // Align the text with the middle of the box
                val textTop = textY + (boxSize.y - fontHeight) / 2
                val lineY = textTop + fontHeight
                val textEndY = drawText(lineY, text, colour, rebuildBBs, maxTextX)

                if (textEndY == lineY) {
                    // The event is only one line long, draw the resource
                    // box after the text.
                    val boxX = textX + textFont.getWidth(text) + textResourceMargin
                    val boxPos = ConstPoint(boxX, textY)
                    drawResourceBox(g, option.choiceResources, boxPos, boxSize, if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsColors.resourceBoxColour(colour) else colour)
                } else {
                    // The text was multiple lines long, draw the resource box
                    // on the right-mode side and centred on the text.
                    // This does leave a notably larger spacing between the line
                    // above as space was made for the box, but that's there in
                    // vanilla FTL too (though slightly shorter - though it's
                    // close enough, and I've had enough of fiddling with
                    // layouts to care about this particular inconsistency).
                    val firstLineTop = lineY - fontHeight
                    val textHeight = textEndY - firstLineTop
                    val centreY = firstLineTop + textHeight / 2 - boxSize.y / 2
                    val boxPos = ConstPoint(maxBoxX, centreY)
                    drawResourceBox(g, option.choiceResources, boxPos, boxSize, if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsColors.resourceBoxColour(colour) else colour)
                }

                // ChoiceBox::OnRender: the row is as tall as the box or the text and 12 px, 10 px below the last row,
                // and the mouse answers on the box as well as on the text.
                val rowTop = textY
                val rowHeight = max(boxSize.y, textEndY - lineY + textLineHeight + 12)
                textY = rowTop + RESOURCE_BOTTOM_MARGIN + rowHeight
                if (rebuildBBs && optionBoundingBoxes.isNotEmpty()) {
                    val rowWidth = if (textEndY == lineY) textFont.getWidth(text) + textResourceMargin + boxSize.x else maxBoxX + boxSize.x - textX
                    optionBoundingBoxes[optionBoundingBoxes.lastIndex] = Rectangle(textX.f, rowTop + 3f, rowWidth.f, rowHeight.f)
                }
            } else {
                textY += optionTopOffset
                textY = drawText(textY, text, colour, rebuildBBs)
                textY += optionBottomMargin
            }
        }
        return textY
    }

    private fun optionColour(i: Int, choice: Choice?, hsType: Int?, enabled: Boolean): Colour {
        val colour0 = when {
            !enabled -> Constants.TEXT_OPTION_DISABLED
            hoveredOption == i -> Constants.TEXT_OPTION_HOVER
            hsType != null -> if (hsType == 2) Constants.TEXT_OPTION_BLUE else Colour.white
            choice == null -> Colour.white
            choice.blue -> Constants.TEXT_OPTION_BLUE
            else -> Colour.white
        }

        // Hyperspace customChoiceColors: the choice's <text color> set replaces this colour (HsColors.choiceText)
        return if (xyz.znix.xftl.hs.HS.active && choice?.hsTextColor != null) xyz.znix.xftl.hs.render.HsColors.choiceText(choice.hsTextColor, colour0) else colour0
    }

    /** The touch layout's choices, each in a box of its own; returns the y after the last one. */
    private fun drawTouchRows(g: Graphics, top: Int, rebuildBBs: Boolean): Int {
        var rowY = top
        for ((i, option) in options.withIndex()) {
            val choice = option.choice
            val enabled = isConditionsSatisfied(option)
            val colour = if (enabled) optionColour(i, choice, hsTypes?.getOrNull(i), true) else TOUCH_ROW_DISABLED

            var text = "${i + 1}. " + option.choiceText
            var rewards: ResourceSet? = null
            if (!option.isContinue) {
                if (option.created?.crew != null && choice?.hidden != true && game.isPlayerCrewFull)
                    text += game.translator["event_crew_full"]
                rewards = option.choiceResources.takeIf { it.hasAnything }
            }
            rowY = drawTouchRow(g, text, colour, enabled, rewards, rowY, rebuildBBs)
        }
        return rowY
    }

    /**
     * One boxed choice: the text 10 px in from the body text and wrapped at 540, with any rewards right after it (or at
     * the end of the shorter wrap). [rowY] is 10 px above the box, the next row starts 15 px below it.
     */
    private fun drawTouchRow(g: Graphics, text: String, colour: Colour, enabled: Boolean, rewards: ResourceSet?, rowY: Int, rebuildBBs: Boolean): Int {
        val f = textFont
        val x = textX + 10
        val rewardSize = rewards?.let { findResourceBoxSize(it) }
        val fullWidth = f.getWidth(text)
        val wrap = when {
            rewardSize == null -> TOUCH_ROW_WRAP
            fullWidth + rewardSize.x <= TOUCH_ROW_WRAP -> fullWidth + 10
            else -> TOUCH_ROW_WRAP - 20 - rewardSize.x
        }
        val lines = f.wrapString(text, wrap)
        val textWidth = lines.maxOf { f.getWidth(it) }
        val textHeight = lines.size * textLineHeight

        // Font 13 sits lower in its lines, so its box starts higher.
        val smallFix = if (textSize == 13) 1 else 0
        val top = rowY + 10
        // The frame stands clear of the text above and below it.
        val boxTop = top - 2 * smallFix - TOUCH_ROW_PAD
        val boxHeight = max(textHeight + 2 + smallFix + 2 * TOUCH_ROW_PAD, rewardSize?.y ?: 0)
        val boxX = x - 7
        val boxWidth = textWidth + 14

        g.colour = if (enabled) TOUCH_ROW_FILL else TOUCH_ROW_FILL_DISABLED
        g.fillRect(boxX.f, boxTop.f, boxWidth.f, boxHeight.f)
        g.colour = colour
        g.fillRect(boxX.f, boxTop.f, boxWidth.f, 2f)
        g.fillRect(boxX.f, boxTop + boxHeight - 2f, boxWidth.f, 2f)
        g.fillRect(boxX.f, boxTop.f, 2f, boxHeight.f)
        g.fillRect(boxX + boxWidth - 2f, boxTop.f, 2f, boxHeight.f)

        var lineY = boxTop + 2 * smallFix + max(0, boxHeight - textHeight - 2 - smallFix) / 2 + textBaseline
        for (line in lines) {
            f.drawString(x.f, lineY.f, line, colour)
            lineY += textLineHeight
        }

        if (rewards != null && rewardSize != null) {
            val boxColour = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsColors.resourceBoxColour(colour) else colour
            drawResourceBox(g, rewards, ConstPoint(x + wrap, boxTop + (boxHeight - rewardSize.y) / 2), rewardSize, boxColour)
        }

        if (rebuildBBs)
            optionBoundingBoxes += Rectangle(boxX.f, boxTop.f, boxWidth.f, boxHeight.f)

        return boxTop + boxHeight + 15
    }

    /**
     * The touch layout's hit test: the rows' bands reach each other, half of the gap each way, with 10 px beyond the box
     * at the sides and outside the first and last rows. Unavailable rows take no touches.
     */
    private fun touchRowAt(x: Int, y: Int): Int? {
        var hit: Int? = null
        val last = optionBoundingBoxes.lastIndex
        for ((i, bb) in optionBoundingBoxes.withIndex()) {
            if (syntheticEvents.isEmpty() && options.getOrNull(i)?.let { isConditionsSatisfied(it) } != true) continue
            val above = if (i == 0) 10f else TOUCH_ROW_GAP / 2f
            val below = if (i == last) 10f else TOUCH_ROW_GAP / 2f
            if (x >= bb.x - 10f && x <= bb.x + bb.width + 10f && y >= bb.y - above && y <= bb.y + bb.height + below)
                hit = i
        }
        return hit
    }

    /**
     * Hyperspace WorldManager::CreateChoiceBox hook (CustomAugments.cpp): the scrap the box shows is raised by the
     * scrap augment fraction (display only; the event's own application already did it), unless the event has
     * disableScrapAugments.
     */
    private fun hsChoiceScrap(set: ResourceSet, created: CreatedEvent?): ResourceSet {
        if (!xyz.znix.xftl.hs.HS.active || set.scrap <= 0) return set
        if (created != null && xyz.znix.xftl.hs.events.HsFlow.customFor(game, created)?.disableScrapAugments == true) return set
        val v = xyz.znix.xftl.hs.ship.HsAugments.choiceTextScrap(playerShip)
        if (v == 0f) return set
        val copy = ResourceSet()
        copy += set
        copy.scrap = (set.scrap.toFloat() + set.scrap.toFloat() * v).toInt()
        return copy
    }

    private fun drawEventOutcome(g: Graphics, text: String, resourcesGained: ResourceSet): Int {
        // The text's top is fixed; the choices start gap_size (30) below its last line (ChoiceBox::OnRender).
        var textY = position.y + 23 + textBaseline
        textY = drawText(textY, text)
        val belowText = textLineHeight - textBaseline

        if (resourcesGained.hasAnything) {
            // The box is centred 43 pixels below the baseline of the last line of
            // the event text (with font 12; 5px below the choices' start).
            val boxMiddleY = textY + belowText + 35

            drawOutcomeBox(g, resourcesGained, position.x + 295, boxMiddleY)

            // The spacing between the event text and the options is constant, regardless
            // of the size of the rewards.
            textY += belowText + 87
        } else {
            textY += belowText + 37
        }

        // At this point, textY is where the choices should go.

        return textY
    }

    private fun drawSyntheticEvent(g: Graphics, event: SyntheticEvent) {
        val textY = drawEventOutcome(g, event.text, hsChoiceScrap(event.resources, null))

        val rebuildBBs = optionBoundingBoxes.isEmpty()

        // Draw the continue option
        val colour = when (hoveredOption) {
            0 -> Constants.TEXT_OPTION_HOVER
            else -> Colour.white
        }

        val text = "1. " + game.translator["continue"]
        if (TouchMode.enabled) drawTouchRow(g, text, colour, true, null, textY - TOUCH_CHOICES_UP, rebuildBBs)
        else drawText(textY, text, colour, rebuildBBs)
    }

    /** The measured outcome box: its size and, when there is a message line, the first row's size. */
    private class OutcomeLayout(val width: Int, val height: Int, val rowWidth: Int, val rowHeight: Int)

    /**
     * ChoiceBox::PrintResourceBox (0x004fb9c0) for an event's outcome: measured by a first pass
     * (0x004fb680), the box is centred on [centreX] and [middleY], 5px wider than its contents, and the
     * contents are laid out again inside it (0x004f7f10 in its one-row mode).
     */
    private fun drawOutcomeBox(g: Graphics, set: ResourceSet, centreX: Int, middleY: Int) {
        val measured = layoutOutcome(g, set, 0, 0, null)
        if (measured.width == 0 || measured.height == 0)
            return
        val boxX = centreX - measured.width / 2
        val boxY = middleY - measured.height / 2

        g.colour = Constants.REWARDS_BACKGROUND
        g.fillRect(boxX.f, boxY.f, measured.width + 5f, measured.height.f)
        g.colour = Colour.white
        g.drawRect(boxX.f, boxY.f, measured.width + 4f, measured.height - 1f)
        g.drawRect(boxX + 1f, boxY + 1f, measured.width + 2f, measured.height - 3f)

        // A row taller than 32px centres its contents.
        var rowY = boxY
        if (measured.height > 32) {
            rowY += if (measured.rowHeight == 0) (measured.height - 32) shr 1 else (measured.rowHeight - 32) / 2
        }
        layoutOutcome(g, set, boxX, rowY, measured)
    }

    /**
     * 0x004f7f10 with the outcome flag: one row of fuel, missiles, drone parts and scrap
     * (ChoiceBox::RenderResource 0x004f7550 steps 45px, 50px from 10 on, 5px more when negative), then
     * 20px on the weapon, drone, augment and crew; intruders, fleet pursuit, hull damage, healing and
     * upgrades share one line below, five spaces apart, in one colour, centred under the row. With
     * [measured] null this only measures (the native measure pass draws in a transparent colour).
     * Text y positions are the native glyph tops (row + 9), drawn 12px lower for our baselines; text
     * widths are pen advances, as the native draw call returns them.
     */
    private fun layoutOutcome(g: Graphics?, set: ResourceSet, boxX: Int, boxY: Int, measured: OutcomeLayout?): OutcomeLayout {
        val draw = measured != null
        var y = boxY
        var rowHeight = 32
        var firstRowWidth = 0
        var firstRowHeight = 0
        val x = if (measured != null && measured.rowWidth != 0) boxX + (measured.width - measured.rowWidth) / 2 else boxX

        var cx = 5
        for ((resource, amount) in set.toList().sortedBy { it.first.ordinal }) {
            if (draw) {
                val colour = if (amount > 0) Constants.REWARDS_ICONS else Constants.REWARDS_NEGATIVE_ICONS
                resource.getIcon(game).draw((x + cx).f, y.f, colour)
                resourceNumFont.drawString(x + cx + 30f, y + 21f, amount.toString(), Colour.white)
            }
            cx += (if (abs(amount) < 10) 45 else 50) + (if (amount < 0) 5 else 0)
        }
        if (cx >= 6)
            cx += 20

        var any = false
        for (bp in set.items.filterIsInstance<AbstractWeaponBlueprint>()) {
            // WeaponBlueprint::GetDimensions (0x00603c80): the launcher as mounted, so turned on its side.
            val image = bp.getLauncher(game).getChargedImage(game)
            // Hyperspace <iconReplace>: GetDimensions is the replacement's frame size.
            val replaced = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsWeapons.iconSize(bp, game) else null
            val w = replaced?.x ?: image.height
            val h = replaced?.y ?: image.width
            val name = game.translator[bp.title!!]
            if (draw) {
                if (replaced != null) xyz.znix.xftl.hs.weapons.HsWeapons.drawIcon(bp, bp.hs!!, game, image, g!!,
                    (x + cx + w / 2 - (w + 1) / 2).f + w / 2f, (y + 16 - h / 2).f + h / 2f)
                else bp.drawLauncherUI(image, g!!, (x + cx + w / 2 - (w + 1) / 2).f, (y + 16 - h / 2).f, game)
                resourceNumFont.drawString(x + cx + 10f + w, y + 21f, name, Colour.white)
            }
            cx += 20 + w + advance(resourceNumFont, name)
            rowHeight = max(rowHeight, h + 9)
            any = true
        }
        for (bp in set.items.filterIsInstance<DroneBlueprint>()) {
            val (w, h) = droneRewardSize(bp)
            val indoor = bp.type == DroneBlueprint.DroneType.REPAIR || bp.type == DroneBlueprint.DroneType.BATTLE ||
                bp.type == DroneBlueprint.DroneType.BOARDER
            if (!indoor)
                cx -= 16
            val name = game.translator[bp.title!!]
            if (draw) {
                bp.drawIconUI(game, ConstPoint(x + cx + w / 2, y + 16))
                resourceNumFont.drawString(x + cx + w + (if (indoor) 15f else -10f), y + 21f, name, Colour.white)
            }
            if (indoor) {
                cx += advance(resourceNumFont, name) + 20 + w
            } else {
                cx += advance(resourceNumFont, name) - 4 + w
                rowHeight = max(rowHeight, h - 16)
            }
            any = true
        }
        for (bp in set.items.filterIsInstance<AugmentBlueprint>()) {
            val label = game.translator["augment"]
            val name = bp.translateTitle(game)
            if (draw)
                font.drawString((x + cx).f, y + 21f, label, Colour.white)
            cx += advance(font, label) + 5
            if (draw)
                resourceNumFont.drawString((x + cx).f, y + 21f, name, Colour.white)
            cx += advance(resourceNumFont, name) + 5
            any = true
        }

        val gainedRows = set.crew.map { Triple(it.name, it, null as RemoveCrewEval?) }
        val lostRows = set.lostCrew.map { lost ->
            val key = if (lost.info.turnHostile) "traitor_crew" else "dead_crew"
            Triple(game.translator[key].replaceArg(lost.crew.info.name), null as LivingCrewInfo?, lost)
        }
        for ((text, gained, lost) in gainedRows + lostRows) {
            if (cx == 5 || any)
                cx += 20
            val textX = cx + 15
            if (draw) {
                gained?.drawPortrait(x + cx - 17, y - 2)
                lost?.crew?.drawPortrait(x + cx - 17, y - 2, false)
                val colour = if (lost != null) Constants.SYS_ENERGY_BROKEN else Colour.white
                resourceNumFont.drawString((x + textX).f, y + 21f, text, colour)
            }
            cx = advance(resourceNumFont, text) + 15 + textX
            // The first two skills the crew starts with (CrewBlueprint::RenderSkillIcon 0x00604200).
            val skills = gained?.let { info -> Skill.values().filter { info.skills.getValue(it) > 0f } }.orEmpty()
            if (skills.isEmpty()) {
                cx -= 7
            } else {
                for ((i, skill) in skills.take(2).withIndex()) {
                    val iconX = if (i == 0) cx - 10 else cx
                    if (draw) {
                        val colour = when (gained!!.getSkillLevel(skill)) {
                            SkillLevel.MAX -> Constants.SYS_ENERGY_REPAIR
                            SkillLevel.PARTIAL -> Constants.SYS_ENERGY_ACTIVE
                            else -> Constants.UI_BACKGROUND_GLOW_COLOUR
                        }
                        game.getImg(skill.iconPath).draw((x + iconX).f, (y + 2).f, colour)
                    }
                    cx += if (i == 0) 13 else 23
                }
                cx += 5
            }
            any = true
        }

        val messages = outcomeMessages(set)
        if (messages != null) {
            val (text, colour) = messages
            val textX: Int
            if (cx < 6 && !any) {
                // Nothing above: the line starts 30px in.
                rowHeight = 32
                cx = 30
                textX = x + cx
            } else {
                y += 28
                firstRowWidth = cx
                firstRowHeight = rowHeight
                rowHeight = 24
                textX = x + firstRowWidth / 2 - advance(resourceNumFont, text) / 2
                cx = 25
            }
            if (draw)
                resourceNumFont.drawString(textX.f, y + 21f, text, colour)
            // 0x00595f30 measures the printed line's extent.
            cx += resourceNumFont.getExtentWidth(text) + 25
        } else if (!any) {
            if (cx < 6)
                return OutcomeLayout(0, 0, 0, 0)
            cx -= 25
        }

        return OutcomeLayout(max(cx, firstRowWidth), rowHeight + firstRowHeight, firstRowWidth, firstRowHeight)
    }

    /** For the parity checks: the outcome box's measured contents (drawn 5px wider). */
    internal fun diagnosticOutcomeSize(set: ResourceSet): IPoint =
        layoutOutcome(null, set, 0, 0, null).let { ConstPoint(it.width, it.height) }

    /**
     * The outcome's message line (0x004f7f10): intruders, fleet pursuit, hull damage, healing and upgrades,
     * five spaces apart. It is red when anything bad is in it, green when it only heals, delays the fleet or
     * upgrades, and white for a failed upgrade.
     */
    private fun advance(font: SILFontLoader, text: String): Int = font.getAdvance(text).toInt()

    private fun outcomeMessages(set: ResourceSet): Pair<String, Colour>? {
        val parts = ArrayList<String>()
        if (set.hasIntruders)
            parts += game.translator["intruder_alert"]
        if (set.modifyPursuit != 0)
            parts += getFleetPursuitText(set.modifyPursuit).first
        val damage = set.damage.sumOf { it.amount }
        val damageText = if (set.damage.isNotEmpty()) getHullDamageText(set).first else null
        if (damageText != null)
            parts += damageText
        var upgradeSucceeded = false
        for (upgrade in set.upgrades) {
            val (text, colour) = getUpgradeText(upgrade)
            parts += text
            upgradeSucceeded = upgradeSucceeded || colour == Constants.SYS_ENERGY_ACTIVE
        }
        if (parts.isEmpty())
            return null

        val bad = set.hasIntruders || set.modifyPursuit > 0 || damage > 0
        val colour = when {
            bad -> Constants.SYS_ENERGY_BROKEN
            damage < 0 || set.modifyPursuit < 0 || upgradeSucceeded -> Constants.SYS_ENERGY_ACTIVE
            else -> Colour.white
        }
        return Pair(parts.joinToString("     "), colour)
    }

    /**
     * DroneBlueprint::GetDimensions (0x00603df0): indoor drones measure their first walk-down frame, the
     * others their charged hull; shield drones add 16x22px, defence drones and COMBAT_BEAM_2 8px of height.
     */
    private fun droneRewardSize(bp: DroneBlueprint): Pair<Int, Int> {
        val indoor = when (bp.type) {
            DroneBlueprint.DroneType.REPAIR -> "repair"
            DroneBlueprint.DroneType.BATTLE, DroneBlueprint.DroneType.BOARDER -> if (bp.name == "BOARDER_ION") "boarder_ion" else "battle"
            else -> null
        }
        if (indoor != null) {
            val anim = game.animations.animations["${indoor}_walk_down"] ?: return Pair(1, 1)
            return Pair(anim.sheet.frameWidth, anim.sheet.frameHeight)
        }
        val image = bp.droneImage?.let { game.getImgIfExists("img/ship/drones/${it}_charged.png") } ?: return Pair(1, 1)
        return when {
            bp.type == DroneBlueprint.DroneType.SHIELD -> Pair(image.width + 16, image.height + 22)
            bp.type == DroneBlueprint.DroneType.DEFENSE || bp.name == "COMBAT_BEAM_2" -> Pair(image.width, image.height + 8)
            else -> Pair(image.width, image.height)
        }
    }

    private fun findResourceBoxSize(resourceSet: ResourceSet): IPoint {
        var width = RESOURCE_LEFT_START

        // Add up the width used by each of fuel/scrap/drones/missiles
        for ((_, amount) in resourceSet.entries) {
            // For the resource icon and it's padding to the number
            width += RESOURCE_ICON_SPACING

            width += resourceNumFont.getWidth(amount.toString())

            // The padding until the next number, or the end of the box
            width += RESOURCE_RIGHT_SPACING
        }

        var height = 0

        if (resourceSet.isNotEmpty())
            height += 32

        for (bp in resourceSet.items) {
            val bpWidth: Int = when (bp) {
                is AbstractWeaponBlueprint -> {
                    val nameWidth = resourceNumFont.getWidth(game.translator[bp.title!!])
                    val img = bp.getLauncher(game).getChargedImage(game)
                    height += img.width + 9
                    10 + img.height + 10 + nameWidth + 16
                }

                is DroneBlueprint -> {
                    val nameWidth = resourceNumFont.getWidth(game.translator[bp.title!!])

                    // Not sure if +9 is appropriate, or iconSize should be bigger.
                    height += bp.iconSize.y + 9

                    10 + bp.iconSize.x + 8 + nameWidth + 12 // Approximate numbers
                }

                is AugmentBlueprint -> {
                    height += 32

                    val nameWidth = resourceNumFont.getWidth(bp.translateTitle(game))
                    10 + augmentStringWidth + 8 + nameWidth + 11
                }

                else -> error("Can't draw non-weapon/drone/augment blueprint: $bp")
            }

            width = max(width, bpWidth)
        }

        for (crew in resourceSet.crew) {
            // There's 30 pixels for the crew member to fit on the left of their name
            val crewWidth = 30 + resourceNumFont.getWidth(crew.name) + 14
            width = max(width, crewWidth)

            // Always 32 pixels high
            height += 32
        }

        for (crew in resourceSet.lostCrew) {
            val messageKey = if (crew.info.turnHostile) "traitor_crew" else "dead_crew"
            val message = game.translator[messageKey].replaceArg(crew.crew.info.name)

            // There's 30 pixels for the crew member to fit on the left
            // of the 'so-and-so is gone' message.
            val crewWidth = 30 + resourceNumFont.getWidth(message) + 14
            width = max(width, crewWidth)

            // Always 32 pixels high
            height += 32
        }

        if (resourceSet.hasIntruders) {
            // Measured against the original: the box is 59px wider than the text's advance.
            val message = game.translator["intruder_alert"]
            width = max(width, 32 + resourceNumFont.getAdvanceWidth(message) + 27)
            height += 32
        }

        if (resourceSet.damage.isNotEmpty()) {
            val (message, _) = getHullDamageText(resourceSet)
            if (message != null) {
                width = max(width, 30 + resourceNumFont.getWidth(message) + 30)
                height += 32
            }
        }

        for (upgrade in resourceSet.upgrades) {
            val (message, _) = getUpgradeText(upgrade)
            width = max(width, 25 + resourceNumFont.getWidth(message) + 30)
            height += 32
        }

        if (resourceSet.modifyPursuit != 0) {
            val (message, _) = getFleetPursuitText(resourceSet.modifyPursuit)
            width = max(width, 24 + resourceNumFont.getWidth(message) + 31)
            height += 32
        }

        return ConstPoint(width, height)
    }

    private fun drawResourceBox(g: Graphics, resourceSet: ResourceSet, pos: IPoint, size: IPoint, textColour: Colour) {
        var y = pos.y

        g.colour = Constants.REWARDS_BACKGROUND
        g.fillRect(pos.x.f, y.f, size.x.f, size.y.f)

        g.colour = textColour
        g.drawRect(pos.x.f, y.f, size.x - 1f, size.y - 1f)
        g.drawRect(pos.x.f + 1, y + 1f, size.x - 3f, size.y - 3f)

        var resourceX = pos.x + RESOURCE_LEFT_START

        for (pair in resourceSet.toList().sortedBy { it.first.ordinal }) {
            // Draw the icon
            val colour = if (pair.second > 0) Constants.REWARDS_ICONS else Constants.REWARDS_NEGATIVE_ICONS
            pair.first.getIcon(game).draw(resourceX.f, y.f, colour)
            resourceX += RESOURCE_ICON_SPACING

            // Draw the text
            val numText = pair.second.toString()
            resourceNumFont.drawString(resourceX.f, y + 21f, numText, textColour)
            resourceX += resourceNumFont.getWidth(numText)

            // Add some padding before the next resource
            resourceX += RESOURCE_RIGHT_SPACING
        }

        // If we drew some resources, move the blueprints down so they don't overlap
        if (resourceSet.isNotEmpty()) {
            y += 32
        }

        for (crew in resourceSet.crew) {
            y += drawRewardCrew(crew, pos.x, y, textColour)
        }

        for (crew in resourceSet.lostCrew) {
            y += drawLostCrew(crew, pos.x, y)
        }

        for (bp in resourceSet.items) {
            y += drawRewardBlueprint(g, bp, pos.x, y, textColour)
        }

        if (resourceSet.hasIntruders) {
            val message = game.translator["intruder_alert"]
            resourceNumFont.drawString(pos.x + 30f, y + 21f, message, Constants.SYS_ENERGY_BROKEN)
            y += 32
        }

        if (resourceSet.damage.isNotEmpty()) {
            val (message, colour) = getHullDamageText(resourceSet)
            if (message != null) {
                resourceNumFont.drawString(pos.x + 30f, y + 21f, message, colour)
                y += 32
            }
        }

        for (upgrade in resourceSet.upgrades) {
            val (message, colour) = getUpgradeText(upgrade)
            resourceNumFont.drawString(pos.x + 25f, y + 21f, message, colour)
            y += 32
        }

        if (resourceSet.modifyPursuit != 0) {
            val (message, colour) = getFleetPursuitText(resourceSet.modifyPursuit)
            resourceNumFont.drawString(pos.x + 24f, y + 21f, message, colour)
            y += 32
        }
    }

    private fun drawText(
        y: Int, msg: String, colour: Colour = Colour.white,
        addOption: Boolean = false, maxX: Int = -1
    ): Int {
        // The x coordinate where line wrapping is triggered: ChoiceBox::OnRender
        // (0x004fc0f0) wraps its text 565px from where it starts.
        val textFarX = if (maxX != -1) maxX.f else textX + 565f
        val maxWidth = textFarX - textX

        var textY = y
        var longestLine = 0

        val f = textFont
        val lineHeight = textLineHeight
        for (line in f.wrapString(msg, maxWidth.toInt())) {
            longestLine = longestLine.coerceAtLeast(f.getWidth(line))
            f.drawString(textX.f, textY.f, line, colour)
            textY += lineHeight
        }

        // Remove the space used by the last line.
        textY -= lineHeight

        if (addOption) {
            optionBoundingBoxes += Rectangle(textX.f, y.f - fontHeight, longestLine.f, textY - y + lineHeight - 6f)
        }

        return textY
    }

    private fun drawRewardBlueprint(g: Graphics, bp: Blueprint, boxX: Int, textY: Int, textColour: Colour): Int {
        when (bp) {
            is AbstractWeaponBlueprint -> {
                val anim = bp.getLauncher(game)

                bp.drawLauncherUI(game, g, boxX + 10f, textY + 2f)

                // Draw the name
                val name = game.translator[bp.title!!]
                val chargedImage = anim.getChargedImage(game)
                resourceNumFont.drawString(boxX + chargedImage.height + 20f, textY + 22f, name, textColour)

                return 42
            }

            is DroneBlueprint -> {
                // Draw the drone icon - the Y here isn't right, but it's good enough for now. TODO do it properly.
                val height = bp.iconSize.y + 9
                val imgCentreY = textY + height / 2
                bp.drawIconUI(game, ConstPoint(boxX + 21, imgCentreY))

                // Draw the name
                val name = game.translator[bp.title!!]
                val textX = boxX + 10 + bp.iconSize.x + 8
                resourceNumFont.drawString(textX.f, imgCentreY.f + FONT_HEIGHT / 2, name, textColour)

                return 20
            }

            is AugmentBlueprint -> {
                val nameX = boxX + 10 + augmentStringWidth + 8
                val nameY = textY + 21f

                font.drawString(boxX + 10f, nameY, game.translator["augment"], textColour)
                resourceNumFont.drawString(nameX.f, nameY, bp.translateTitle(game), textColour)

                return 32
            }

            else -> error("Can't draw non-weapon/drone/augment blueprint: $bp")
        }
    }

    private fun drawRewardCrew(crew: LivingCrewInfo, x: Int, y: Int, textColour: Colour): Int {
        crew.drawPortrait(x - 2, y - 2)

        resourceNumFont.drawString(x + 30f, y + 21f, crew.name, textColour)

        return 32
    }

    private fun drawLostCrew(crew: RemoveCrewEval, x: Int, y: Int): Int {
        crew.crew.drawPortrait(x - 2, y - 2, false)

        val messageKey = if (crew.info.turnHostile) "traitor_crew" else "dead_crew"
        val message = game.translator[messageKey].replaceArg(crew.crew.info.name)
        resourceNumFont.drawString(x + 30f, y + 21f, message, Constants.SYS_ENERGY_BROKEN)

        return 32
    }

    /**
     * Checks if we've got all the stuff required for an event (both
     * consumables and scrap if they're subtracted by the event, and
     * also equipment/crew used for blue options).
     */
    private fun isConditionsSatisfied(event: EvaluatedEvent): Boolean {
        if (event.isContinue)
            return true

        // Hyperspace: the box decided when it was made (CheckRequirements with HS's hook).
        hsTypes?.let { types ->
            val i = options.indexOf(event)
            if (i >= 0) return types[i] != 1
        }

        val res = event.previewResources

        // WorldManager::CheckRequirements: the costs (not for a stealing event) and the repair station's rule
        if (!xyz.znix.xftl.sector.NativeRequirements.check(playerShip, res.fuel, res.missiles, res.droneParts, res.scrap, event.event))
            return false
        if (systemChoiceWarning(event)?.second == true)
            return false

        return hasRequiredEquipment(event)
    }

    /**
     * Checks if the player has all the stuff requiredd for an event,
     * excluding resources.
     *
     * This is distinct from [isConditionsSatisfied], since the option is hidden
     * if the user lacks any of the required equipment.
     */
    private fun hasRequiredEquipment(event: EvaluatedEvent): Boolean {
        if (event.isContinue)
            return true

        // Check for the option requirement, which is usually used
        // for blue-option requirements.
        return event.choice?.let { checkReq(it) } ?: true
    }

    /**
     * CreateChoiceBox (1.6.14 0x616680) keeps a choice with a `req` only when ShipObject::HasEquipment(req) (0x46d350)
     * lies between its lvl and max_lvl (EventsParser::ProcessChoice defaults 1 and INT_MAX). HasEquipment reads the
     * ship's equipment list: a system or the reactor by level, each crew member, weapon, drone and augment once
     * (ShipObject::AddEquipment's callers are WeaponSystem::AddWeapon, DroneSystem::AddDrone, AddSystem and the crew;
     * ShipInfo::AddAugmentation counts augments), so the cargo hold never counts (Hyperspace's checkCargo adds it);
     * a blueprint list is 1 when any entry is there. maxGroup is applied when the box is made.
     */
    private fun checkReq(choice: Choice): Boolean {
        val req = choice.req ?: return true
        val have = xyz.znix.xftl.hs.events.HsReq.equipList(game, playerShip, req)
        return have >= (choice.minLevel ?: 1) && have <= (choice.maxLevel ?: Int.MAX_VALUE)
    }

    private fun requiredCrewCount(req: String): Int? {
        val blueprint = game.blueprintManager.blueprints[req]
        val races = when (blueprint) {
            is xyz.znix.xftl.crew.CrewBlueprint -> setOf(req)
            is BlueprintList -> blueprint.list().filterIsInstance<xyz.znix.xftl.crew.CrewBlueprint>().map { it.name }.toSet().takeIf { it.isNotEmpty() } ?: return null
            else -> return null
        }
        // Owned crew may be aboard either ship. Intruders and drones do not
        // satisfy crew requirements, including while mind controlled.
        return game.playerCrew.count { it.health > 0f && it.codename in races }
    }

    private fun crewRequirementSatisfied(choice: Choice): Boolean {
        val count = requiredCrewCount(choice.req ?: return false) ?: return false
        return count >= (choice.minLevel ?: 1) && count <= (choice.maxLevel ?: Int.MAX_VALUE)
    }

    // ChoiceBox::MouseMove (0x4f6ab0) marks the pointer valid over any choice but an unavailable one.
    override val cursorValid: Boolean
        get() {
            val idx = hoveredOption ?: return super.cursorValid
            return syntheticEvents.isNotEmpty() || options.getOrNull(idx)?.let { isConditionsSatisfied(it) } == true
        }

    override fun updateUI(x: Int, y: Int) {
        super.updateUI(x, y)

        // The bounding boxes are in window coordinates; convert so they
        // still match when the window is drawn scaled (identity when
        // renderScale is 1). super.updateUI converts for the buttons
        // itself - don't pass it the converted point.
        val p = scaleWindowPoint(x, y)

        hoveredOption = null
        // Hyperspace ScrollingChoiceBox::MouseMove: choices answer only inside the box, at the scrolled position
        val py = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsScrollingChoiceBox.mouseMove(game, p.x, p.y) ?: return else p.y
        if (TouchMode.enabled) {
            // The row under a finger that is down, waiting to tap it (TouchRouter) or pressing.
            if (touchFingerDown || TouchRouter.fingerDown) hoveredOption = touchRowAt(p.x, py)
            return
        }
        for ((i, bb) in optionBoundingBoxes.withIndex()) {
            if (bb.contains(p.x.f, py.f)) {
                hoveredOption = i
            }
        }
    }

    /** What a finger taps (TouchRouter): a choice (its row), a scroll button; the scroll bar takes the press. */
    override fun liftTarget(x: Int, y: Int): Any? {
        super.liftTarget(x, y)?.let { return it }
        val p = scaleWindowPoint(x, y)
        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.hs.render.HsScrollingChoiceBox.liftTarget(game, p.x, p.y)?.let { return it }
            val py = xyz.znix.xftl.hs.render.HsScrollingChoiceBox.choiceY(p.y) ?: return null
            return touchRowAt(p.x, py)?.let { listOf("choice", it) }
        }
        return touchRowAt(p.x, p.y)?.let { listOf("choice", it) }
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        super.mouseReleased(button, x, y)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsScrollingChoiceBox.mouseUp()
        if (TouchMode.enabled && button == Input.MOUSE_LEFT_BUTTON && touchFingerDown) {
            // The row under the finger as it lifts: sliding off a row cancels.
            updateUI(x, y)
            touchFingerDown = false
            val idx = hoveredOption ?: return
            hoveredOption = null
            if (!choiceInputReady) logChoice("touch", -1, "delay") else chooseInput("touch", idx)
        }
    }

    override fun mouseScroll(change: Int) {
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsScrollingChoiceBox.wheel(change)
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON) return
        if (TouchMode.enabled) touchFingerDown = true
        updateUI(x, y)
        // Android's mouse chooses on its press, as the desktop's does (ChoiceBox::MouseClick).
        if (TouchMode.enabled && TouchRouter.mouseDown) {
            touchFingerDown = false
            if (hoveredOption == null && xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsScrollingChoiceBox.mouseClick(x, y)
            val idx = hoveredOption ?: return
            hoveredOption = null
            if (!choiceInputReady) logChoice("mouse", -1, "delay") else chooseInput("mouse", idx)
            return
        }
        // Hyperspace ScrollingChoiceBox::MouseClick (after the original; a choice closes or replaces the box)
        if (xyz.znix.xftl.hs.HS.active && hoveredOption == null) xyz.znix.xftl.hs.render.HsScrollingChoiceBox.mouseClick(x, y)
        if (TouchMode.enabled) return
        if (!choiceInputReady) { logChoice("mouse", -1, "delay"); return }
        val idx = hoveredOption ?: return
        chooseInput("mouse", idx)
    }

    override fun onTextInput(key: Int, c: Char): Boolean {
        // Desktop event numbers are physical number keys, independent of the
        // configured weapon-slot shortcuts. Consume rejected numbers as well.
        if (key !in '1'.code..'9'.code) return false
        val idx = key - '1'.code
        when {
            choiceMode == EventChoiceHotkeyMode.DISABLED -> logChoice("key", idx, "disabled")
            !choiceInputReady -> logChoice("key", idx, "delay")
            else -> chooseInput("key", idx)
        }
        return true
    }

    private fun logChoice(source: String, idx: Int, result: String) {
        ftl.browser.BrowserDebug.record("ui", "event.choice.input",
            "{\"source\":\"$source\",\"option\":$idx,\"result\":\"$result\",\"mode\":${choiceMode.id},\"openTime\":$openTime}")
    }

    private fun chooseInput(source: String, idx: Int) {
        val result = when {
            syntheticEvents.isNotEmpty() -> if (idx == 0) "accepted" else "out-of-range"
            idx !in options.indices -> "out-of-range"
            !isConditionsSatisfied(options[idx]) -> "disabled-choice"
            else -> "accepted"
        }
        logChoice(source, idx, result)
        if (result == "accepted") selectOption(idx)
    }

    override fun escapePressed() {
        game.shipUI.showPauseWindow()
    }

    /** Diagnostics: the HS name of the event whose box is open (a synthetic event's [SyntheticEvent.hsName] while one shows). */
    /** Diagnostics: the resources choice [index] previews (as the box draws them). */
    fun diagnosticChoiceResources(index: Int): ResourceSet? = options.getOrNull(index)?.takeIf { !it.isContinue }?.choiceResources

    /** Diagnostics: the resources of the open event, as shown and applied (picks the crew it kills if not yet). */
    val diagnosticResources: ResourceSet? get() = if (syntheticEvents.isEmpty()) currentEvent?.takeIf { !it.isContinue }?.resources else null

    val hsOpenEvent: String? get() = if (syntheticEvents.isEmpty()) currentEvent?.created?.template?.let { it.hsName ?: it.debugId } else syntheticEvents.first().hsName

    /** Diagnostics (hs-fullrun reload): the box's internal state. */
    /**
     * Diagnostics: each option as the box lists it: its text, whether it can be picked, and its event's scrap.
     */
    fun diagnosticChoices(): List<Triple<String, Boolean, Int>> = options.map { o ->
        Triple(o.choiceText, isConditionsSatisfied(o), o.created?.scrap ?: 0)
    }

    fun diagnosticState(): String = "event=${currentEvent?.created?.template?.debugId} cont=${currentEvent?.isContinue} synthetic=${syntheticEvents.size} " +
        "pending=${hsPendingChoice != null} depth=$hsDepth types=${hsTypes?.toList()} options=" +
        options.map { "${if (it.isContinue) "continue" else it.created?.template?.debugId}:${isConditionsSatisfied(it)}" }

    /** Diagnostics: the options the player can pick (a synthetic box's Continue is option 0). */
    val diagnosticEnabledOptions: List<Int> get() = if (syntheticEvents.isNotEmpty()) listOf(0) else options.indices.filter { isConditionsSatisfied(options[it]) }.ifEmpty { listOf(0) }

    /** Diagnostics: the number of options the open box offers. */
    val optionCount: Int get() = if (currentEvent != null) options.size else 0

    fun selectOption(idx: Int) {
        // Pressing continue on a synthetic event closes it
        val syntheticEvent = syntheticEvents.firstOrNull()
        if (syntheticEvent != null) {
            if (idx == 0) {
                syntheticEvents.removeAt(0)

                game.givePlayerResources(syntheticEvent.resources)

                // Re-calculate the bounding boxes now the event is visible again
                optionBoundingBoxes.clear()

                val pending = hsPendingChoice
                if (pending != null && syntheticEvents.isEmpty()) {
                    hsPendingChoice = null
                    hsShow(pending, false)
                    return
                }

                // Check if we're out of stuff to show and have to close
                checkClosed()
            }

            return
        }

        // If syntheticEvent is empty, then currentEvent must not be null.
        val event = currentEvent!!

        if (idx < 0 || idx >= options.size)
            return

        val choice = options[idx]

        // Block the selection of disabled options
        if (!isConditionsSatisfied(choice))
            return

        if (choice.choice?.blue == true && crewRequirementSatisfied(choice.choice!!))
            game.browserAchievements.crewBlueChoice()

        // Add any resources the currently-visible event dropped.
        addResources(event)

        if (choice.isContinue) {
            currentEvent = null
            checkClosed()
            return
        }

        if (game.eventManager.hs != null && syntheticEvents.isNotEmpty()) {
            hsPendingChoice = choice
            return
        }
        if (game.eventManager.hs != null) hsShow(choice, false) else loadEvent(choice)
    }

    fun addSyntheticEvent(syntheticEvent: SyntheticEvent) {
        syntheticEvents.add(syntheticEvent)
        syntheticEvent.hsName?.let { name -> syntheticListener?.invoke(name, syntheticEvent.text) }

        // Re-calculate the bounding boxes to only show the continue option.
        optionBoundingBoxes.clear()
    }

    /**
     * The event's status effects, applied when the event is loaded (CreateLocation / UpdateLocation check them
     * before the choice box), not when its box closes: the native last-stand capture shows the flagship's sensors
     * limit with its arrival box open.
     */
    /**
     * StatusEffect::GetNebulaEffect (1.6.9 0x5eba80: {limit, system 7 = sensors, amount 0, target 2 = both ships}),
     * which the original appends to an event whose environment is a nebula or an ion storm (EventGenerator::
     * CreateEvent 0x5b8940) and to a beacon's event the map turns into a nebula location (StarMap::GenerateNebulas
     * 0x5d17e0, TurnIntoFleetLocation 0x5d0940, SetupNoFuel 0x5d0250). Both ships' sensors are capped at 0 there, so
     * their boxes show the capped bars and the unpowered icon (native 1.6.14 capture ps-b1-van b1v-neb). The storm's
     * own effect (reactor divided by 2) is the environment's here (ReactorEnergySource), not a status.
     */
    private fun nebulaStatus(event: EvaluatedEvent): List<EventStatus> {
        val nebula = event.event.environment?.isNebula == true ||
            (event.event === game.currentBeacon.event && game.currentBeacon.nebulaLocation)
        return if (nebula) listOf(NEBULA_STATUS) else emptyList()
    }

    private fun applyStatuses(event: EvaluatedEvent) {
        // WorldManager 0x613a10 / 0x613760 apply each entry to the player's ship when its
        // target is the player or both, then to every enemy ship when it is the enemy or both. The ship system keeps
        // three independent fields (ShipSystem 0x63d1a0..0x63d3e0): the limit (+0x11c), the loss (+0x120) and the
        // divisor (+0x124, which also sets the limit to half the level). Clear resets all three.
        for (effect in event.created?.statusOverride ?: (event.event.statuses + nebulaStatus(event))) {
            val beacon = game.currentBeacon

            for (applyToPlayer in booleanArrayOf(true, false)) {
                val wanted = when (effect.target) {
                    EventStatus.Target.PLAYER -> applyToPlayer
                    EventStatus.Target.ENEMY -> !applyToPlayer
                    EventStatus.Target.BOTH -> true
                }
                if (!wanted)
                    continue

                // With no enemy aboard the enemy half has nothing to apply to.
                val ship = if (applyToPlayer) playerShip else game.enemy ?: continue

                // Hyperspace: the reactor (system id 17) takes the status on its PowerManager (ShipManager 0x49e3f0).
                if (xyz.znix.xftl.hs.HS.active && effect.system == "reactor") {
                    xyz.znix.xftl.hs.systems.HsPower.status(ship, applyToPlayer, effect.op, effect.amount)
                    continue
                }

                // Find the system this effect applies to; an unknown name (ShipSystem 0x63db50 gives -1) does nothing.
                val system = ship.systems.firstOrNull { it.codename == effect.system } ?: continue

                if (applyToPlayer) {
                    when (effect.op) {
                        EventStatus.Operation.CLEAR -> {
                            beacon.powerLimitEffects.remove(system.codename)
                            beacon.powerLossEffects.remove(system.codename)
                        }

                        EventStatus.Operation.LIMIT -> beacon.powerLimitEffects[system.codename] = effect.amount.coerceIn(0..system.energyLevels)
                        EventStatus.Operation.LOSS -> beacon.powerLossEffects[system.codename] = effect.amount
                        EventStatus.Operation.DIVIDE -> if (effect.amount != 1)
                            beacon.powerLimitEffects[system.codename] = system.energyLevels / 2
                    }

                    // It's a bit unnecessary to call this multiple times if multiple
                    // effects are applied, but it's not a notable waste of resources.
                    ship.updateScriptedPowerLimits()
                } else {
                    when (effect.op) {
                        EventStatus.Operation.CLEAR -> {
                            system.scriptedPowerLimit = null
                            system.scriptedPowerLoss = 0
                        }

                        EventStatus.Operation.LIMIT -> system.scriptedPowerLimit = effect.amount
                        EventStatus.Operation.LOSS -> system.scriptedPowerLoss = effect.amount
                        EventStatus.Operation.DIVIDE -> if (effect.amount != 1)
                            system.scriptedPowerLimit = system.energyLevels / 2
                    }
                }
            }
        }
    }

    private fun addResources(event: EvaluatedEvent) {
        val hsCreated = if (game.eventManager.hs != null) event.created else null
        if (hsCreated != null) xyz.znix.xftl.hs.events.HsFlow.beforeModifyResources(game, hsCreated)
        addResourcesOriginal(event)
        if (hsCreated != null) xyz.znix.xftl.hs.events.HsFlow.afterModifyResources(game, hsCreated)
        resourcesListener?.invoke()
    }

    private fun addResourcesOriginal(event: EvaluatedEvent) {
        // ModifyResources (1.6.14 0x615930) kills the crew CreateChoiceBox picked for the box (the event's crew
        // blueprint, LocationEvent +0x4a0; no second pick, native vr-crew-close). An event without text is applied
        // by CreateChoiceBox before it picks (0x616680 calls ModifyResources first): ModifyResources picks the crew it
        // kills itself, and the box's pick follows on the shorter crew list.
        val boxPickPending = event.event.removedCrew.isNotEmpty() && !event.resourcesEvaluated
        game.givePlayerResources(event.resources)
        if (boxPickPending) event.event.pickLostCrew(game, ResourceSet())

        // ResourceEvent::systemId: ShipManager::AddSystem (1.6.14 0x4a8370), as a bought system goes in (the room the
        // ship's blueprint has for it; a clonebay takes over a medbay's level and the reverse, the replaced one goes)
        event.event.systemReward?.let { addEventSystem(it) }

        // Remove blueprints
        removeStuff@ for (name in event.event.removedStuff) {
            val blueprint = game.blueprintManager.getOrNull(name) ?: continue@removeStuff
            if (blueprint is BlueprintList) {
                // Try and remove one item from the list.
                // This isn't used in vanilla, but seems like a logical behaviour
                // that mods will probably use.
                for (item in blueprint.list().shuffled(ftl.browser.BrowserRandom)) {
                    if (playerShip.removeBlueprint(item))
                        continue@removeStuff
                }
            } else {
                // Cast is safe, as IBlueprint is always either
                // Blueprint or BlueprintList.
                playerShip.removeBlueprint(blueprint as Blueprint)
            }
        }

        if (event.event.revealMap) {
            game.currentBeacon.sector.mapRevealed = true
        }
        if (event.event.secretSector) secretSectorPending = true
    }

    private fun addEventSystem(name: String) {
        // an owned one is not offered (the choice is unavailable); one the blueprint has no room for is not added
        if (playerShip.systems.any { it.codename == name }) return
        val slot = playerShip.systemSlots.firstOrNull { it.system.name == name } ?: return
        xyz.znix.xftl.hs.systems.HsSystemInstall.install(playerShip, slot)
        slot.room.system?.let { playerShip.browserPowerNewSystem(it) }
    }

    /**
     * CreateChoiceBox (1.6.14 0x616680, 1.6.9 0x5f0f70) for a choice whose event installs a system: an owned one says
     * new_system_owned and cannot be picked; a subsystem says nothing; a medbay over a clonebay (and the reverse) says
     * it replaces it; one that does not fit (CanFitSystem: fewer than 8 main systems, or that swap) says
     * new_system_full and cannot be picked; else the main system slots left after it (GetSystemCount 7:
     * new_system_last, 6: new_system_penultimate, otherwise new_system_update with 7 - count). Returns the text and
     * whether the choice is unavailable.
     */
    private fun systemChoiceWarning(option: EvaluatedEvent): Pair<String, Boolean>? {
        if (option.isContinue) return null
        val name = option.event.systemReward ?: return null
        val ship = playerShip
        if (ship.systems.any { it.codename == name }) return game.translator["new_system_owned"] to true
        val slot = ship.systemSlots.firstOrNull { it.system.name == name }
        val blueprint = slot?.system ?: game.blueprintManager.getOrNull(name) as? xyz.znix.xftl.systems.SystemBlueprint
        if (blueprint?.info?.isSubSystem == true) return null
        if (name == "medbay" && ship.systems.any { it.codename == "clonebay" }) return game.translator["new_system_medbay"] to false
        if (name == "clonebay" && ship.systems.any { it.codename == "medbay" }) return game.translator["new_system_clonebay"] to false
        val fits = if (slot != null) xyz.znix.xftl.hs.systems.HsSystemInstall.canFit(ship, slot) else ship.mainSystems.size < 8
        if (!fits) return game.translator["new_system_full"] to true
        val count = ship.mainSystems.size
        val key = when (count) { 7 -> "new_system_last"; 6 -> "new_system_penultimate"; else -> null }
        return (if (key != null) game.translator[key] else game.translator["new_system_update"].replace("\\1", (7 - count).toString())) to false
    }

    /**
     * CreateChoiceBox gathers the choices' system warnings, each after [SYSTEM_WARNING_SEPARATOR] but the first, and
     * adds them to the box's own text after one more (1.6.9 0x5f0f70: the event's TextString gets the separator and
     * the warnings; the oracle's ChoiceText.text keeps none, ps-w2-ops).
     */
    private fun systemWarningsText(): String {
        val warnings = options.mapNotNull { systemChoiceWarning(it)?.first }
        return if (warnings.isEmpty()) "" else SYSTEM_WARNING_SEPARATOR + warnings.joinToString(SYSTEM_WARNING_SEPARATOR)
    }

    private fun getHullDamageText(resourceSet: ResourceSet): Pair<String?, Colour> {
        val baseDamage = resourceSet.damage.sumBy { it.amount }

        if (baseDamage < 0) {
            // Limit the displayed health gain if some will be wasted
            // because the player will have full health.
            val realHealing = min(playerShip.maxHealth - playerShip.health, -baseDamage)

            if (realHealing == 0) {
                return Pair(null, Constants.SYS_ENERGY_BROKEN)
            }

            // heal_1_alert for one point (ChoiceBox 0x004f7f10).
            val message = if (realHealing == 1) game.translator["heal_1_alert"]
            else game.translator["heal_alert"].replaceArg(realHealing)
            return Pair(message, Constants.SYS_ENERGY_ACTIVE)
        }

        val realDamage = min(playerShip.health, baseDamage)
        val message = game.translator["damage_alert"].replaceArg(realDamage)
        return Pair(message, Constants.SYS_ENERGY_BROKEN)
    }

    private fun getUpgradeText(upgrade: EventSystemUpgrade): Pair<String, Colour> {
        val isReactor = upgrade.system == "reactor"
        val system = playerShip.systems.firstOrNull { it.codename == upgrade.system }
        val systemName = game.translator[upgrade.system]

        if (system == null && !isReactor) {
            val message = game.translator["upgrade_fail_missing"].replaceArg(systemName)
            return Pair(message, Colour.white)
        }

        val isMaxed = when {
            isReactor -> playerShip.purchasedReactorPower >= playerShip.maxReactorPower
            else -> system!!.energyLevels >= system.maxLevel
        }

        if (isMaxed) {
            val message = game.translator["upgrade_fail_max"].replaceArg(systemName)
            return Pair(message, Colour.white)
        }

        val message = game.translator["upgrade_success"]
            .replaceArg(systemName).replaceArg(upgrade.amount, 2)
        return Pair(message, Constants.SYS_ENERGY_ACTIVE)
    }

    private fun getFleetPursuitText(amount: Int): Pair<String, Colour> {
        when {
            amount == 1 -> {
                val message = game.translator["fleet_speed_1"]
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }

            amount > 1 -> {
                val message = game.translator["fleet_speed"].replaceArg(amount)
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }

            amount == -1 -> {
                val message = game.translator["fleet_delayed_1"]
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }

            else -> {
                val message = game.translator["fleet_delayed"].replaceArg(-amount)
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }
        }
    }

    private fun checkClosed() {
        if (syntheticEvents.isNotEmpty() || currentEvent != null)
            return
        // Hyperspace: an event's HS steps may still load the next event into this window.
        if (hsDepth > 0)
            return

        close()
        if (secretSectorPending) {
            secretSectorPending = false
            game.browserEnterSecretSector()
        }
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        elem.setAttribute("secretSectorPending", secretSectorPending.toString())
        elem.setAttribute("openTime", openTime.toString())
        // Hyperspace box state decided when the box was made (HsFlow.choiceBox types), the quest line loadEvent appended to
        // the text, and the choice waiting behind synthetic events: a reload shows the same box and keeps its options usable.
        hsTypes?.let { elem.setAttribute("hsTypes", it.joinToString(",")) }
        if (extraText.isNotEmpty()) elem.addContent(Element("extraText").setText(extraText))
        hsPendingChoice?.let { pending -> options.indexOf(pending).takeIf { it >= 0 }?.let { elem.setAttribute("hsPending", it.toString()) } }
        currentEvent?.let { event ->
            val currentEventElem = Element("currentEvent")
            saveEEToXML(event, currentEventElem, refs)
            elem.addContent(currentEventElem)
        }

        for (ev in syntheticEvents) {
            val syntheticElem = Element("syntheticEvent")
            SaveUtil.addAttr(syntheticElem, "text", ev.text)
            elem.addContent(syntheticElem)

            val resourcesElem = Element("resources")
            ev.resources.saveToXML(resourcesElem, refs)
            syntheticElem.addContent(resourcesElem)
        }

        for (option in options) {
            val optionElem = Element("option")
            saveEEToXML(option, optionElem, refs, currentEvent)
            elem.addContent(optionElem)
        }
    }

    /**
     * Deserialise an event window from XML
     */
    constructor(game: InGameState, playerShip: Ship, elem: Element, refs: RefLoader, close: () -> Unit)
            : this(game, playerShip, close) {

        secretSectorPending = elem.getAttributeValue("secretSectorPending") == "true"
        openTime = elem.getAttributeValue("openTime")?.toFloatOrNull()?.takeIf { it.isFinite() }?.let(::float32)?.coerceIn(0f, .3f) ?: 0f

        val currentEventElem = elem.getChild("currentEvent")
        if (currentEventElem != null) {
            currentEvent = loadEEFromXML(currentEventElem, refs)
        }

        for (syntheticElem in elem.getChildren("syntheticEvent")) {
            val text = SaveUtil.getAttr(syntheticElem, "text")
            val event = SyntheticEvent(text)

            val resourcesElem = syntheticElem.getChild("resources")
            event.resources += ResourceSet(resourcesElem, refs, game)

            syntheticEvents += event
        }

        options = ArrayList()
        for (optionElem in elem.getChildren("option")) {
            (options as ArrayList).add(loadEEFromXML(optionElem, refs, currentEvent))
        }

        elem.getAttributeValue("hsTypes")?.let { v -> hsTypes = if (v.isEmpty()) IntArray(0) else v.split(',').map { it.toInt() }.toIntArray() }
        elem.getChild("extraText")?.let { extraText = it.text }
        elem.getAttributeValue("hsPending")?.toIntOrNull()?.let { hsPendingChoice = options.getOrNull(it) }
    }

    private fun saveEEToXML(event: EvaluatedEvent, elem: Element, refs: ObjectRefs, parent: EvaluatedEvent? = null) {
        if (event.isContinue) {
            elem.setAttribute("continue", "true")
            return
        }

        SaveUtil.addAttr(elem, "eventId", event.event.deserialisationId)
        SaveUtil.addAttr(elem, "choiceId", event.choice?.deserialisationId ?: "")

        // The created event is saved with its whole tree. An option is
        // saved as the index of its choice instead: it's one of the
        // current event's.
        val choiceIndex = parent?.created?.choices?.indexOfFirst { it.event === event.created } ?: -1
        if (choiceIndex >= 0) {
            elem.setAttribute("choiceIndex", choiceIndex.toString())
        } else {
            val createdElem = Element("created")
            event.created!!.saveToXML(createdElem)
            elem.addContent(createdElem)
        }
    }

    private fun loadEEFromXML(elem: Element, refs: RefLoader, parent: EvaluatedEvent? = null): EvaluatedEvent {
        if (elem.getAttributeValue("continue") == "true") {
            return continueEvent
        }

        elem.getAttributeValue("choiceIndex")?.toIntOrNull()?.let { index ->
            val choice = parent!!.created!!.choices.getOrNull(index) ?: return continueEvent
            return EvaluatedEvent(choice.event, game, choice.template, choice.text)
        }

        val createdElem = elem.getChild("created")
        if (createdElem != null) {
            val created = CreatedEvent.loadFromXML(createdElem, game)
            val choiceId = SaveUtil.getAttr(elem, "choiceId")
            val choice = if (choiceId == "") null else game.eventManager.getSavedChoice(choiceId)
            return EvaluatedEvent(created, game, choice, null)
        }

        // The format before events were created eagerly: the event and
        // choice ids and a seed. The event is created now instead.
        val eventId = SaveUtil.getAttr(elem, "eventId")
        val event = game.eventManager.getSavedEvent(eventId)
        val choiceId = SaveUtil.getAttr(elem, "choiceId")
        val choice = if (choiceId == "") null else game.eventManager.getSavedChoice(choiceId)
        val created = game.eventCreator.createFrom(event, game.eventCreator.currentLevel())
            ?: return continueEvent
        return EvaluatedEvent(created, game, choice, null)
    }

    /**
     * Represents an event that doesn't appear in XML, but is inserted by
     * code when something happens. This includes breaking down duplicate
     * augments, and the crew clone success/failure messages.
     */
    /** [hsName]: the event the text came from (AUGMENT_FULL, EQUIP_FULL), for the diagnostics probe. */
    class SyntheticEvent @JvmOverloads constructor(val text: String, val hsName: String? = null) {
        val resources = ResourceSet()
    }

    /**
     * Represents a created event as the window shows it: the text, the choice text (if applicable)
     * and the resources.
     */
    private class EvaluatedEvent(
        val created: CreatedEvent?,
        game: InGameState,
        val choice: Choice?,
        choiceTextDrawn: EventText?
    ) {
        val isContinue: Boolean = created == null
        val event: Event get() = created?.template ?: throw Exception("Continue does not have an event")
        private val resourcesLazy = lazy { created!!.toResourceSet(game) }

        /** The resources of this event once its box is made: the crew it kills are picked (see [previewResources]). */
        val resources by resourcesLazy
        val resourcesEvaluated: Boolean get() = resourcesLazy.isInitialized()

        /**
         * The resources a parent box shows for this choice, and checks its requirements with: the original never picks
         * the crew a choice would kill for its preview, so neither the pick nor its seed draw happens here.
         */
        val previewResources by lazy { created!!.toResourceSet(game, false) }
        val text: String? get() = created?.text?.localised

        /**
         * If this event is being displayed as a choice inside another event,
         * this is the text it's option uses.
         */
        private val choiceTextBase: String = choiceTextDrawn?.localised ?: game.translator["continue"]

        /** Hyperspace Lua: ChoiceText.text as a script wrote it. */
        var choiceTextOverride: String? = null
        val choiceText: String get() = choiceTextOverride ?: choiceTextBase

        /**
         * The resources that should be displayed in a choice. This filters out
         * crew losses, hidden items, and anything else that shouldn't show up.
         */
        val choiceResources: ResourceSet by lazy {
            val result = ResourceSet()

            // If hidden is set, don't show any resources.
            if (choice?.hidden == true)
                return@lazy result

            result += previewResources
            result.lostCrew.clear()
            result.damage.clear()
            result.upgrades.clear()
            result.modifyPursuit = 0

            if (event.itemsModifySteal) {
                result.scrap = 0
                result.fuel = 0
                result.droneParts = 0
                result.missiles = 0
            }

            // Hyperspace CreateChoiceBox hook: the choice's scrap shows the scrap augment fraction (not for disableScrapAugments)
            if (xyz.znix.xftl.hs.HS.active && result.scrap > 0 &&
                xyz.znix.xftl.hs.events.HsFlow.customFor(game, created)?.disableScrapAugments != true
            ) {
                val v = xyz.znix.xftl.hs.ship.HsAugments.choiceTextScrap(game.player)
                if (v != 0f) result.scrap = (result.scrap.toFloat() + result.scrap.toFloat() * v).toInt()
            }

            return@lazy result
        }
    }

    companion object {
        /** StatusEffect::GetNebulaEffect: the sensors of both ships limited to 0 (see [nebulaStatus]). */
        private val NEBULA_STATUS = EventStatus("sensors", EventStatus.Operation.LIMIT, EventStatus.Target.BOTH, 0)

        /** Diagnostics (hs-events probe): called with the event name and text when a named synthetic event is added. */
        @JvmField var syntheticListener: ((String, String) -> Unit)? = null

        /** Diagnostics: an event's resources (and HS's ModifyResources hooks) were applied. */
        @JvmField var resourcesListener: (() -> Unit)? = null

        private const val FONT_HEIGHT = 11

        private const val RESOURCE_BOTTOM_MARGIN = 10

        /** A space and an empty line: what CreateChoiceBox puts before each system warning (1.6.14 0x884a24). */
        private const val SYSTEM_WARNING_SEPARATOR = " \n\n"

        private const val RESOURCE_LEFT_START = 5
        private const val RESOURCE_ICON_SPACING = 30
        private const val RESOURCE_RIGHT_SPACING = 9

        // The touch layout's boxed rows. The choices start 7 px above where the desktop layout measures from.
        private const val TOUCH_CHOICES_UP = 7
        private const val TOUCH_ROW_WRAP = 540
        private const val TOUCH_ROW_GAP = 30
        private const val TOUCH_ROW_PAD = 6
        private val TOUCH_ROW_FILL = Colour(0f, 0f, 0f, 0.5f)
        private val TOUCH_ROW_FILL_DISABLED = Colour(0f, 0f, 0f, 0.25f)
        private val TOUCH_ROW_DISABLED = Colour(243 * 3 / 4, 255 * 3 / 4, 80 * 3 / 4, 127)
    }
}
