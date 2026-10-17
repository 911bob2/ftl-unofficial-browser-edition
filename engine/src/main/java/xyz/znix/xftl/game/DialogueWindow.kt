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
    Window() {

    override val size: IPoint get() = ConstPoint(602, 377)

    // Touch ergonomics: bigger option text/targets. 1.3x (783x490) fits
    // the canvas; nudged left+up so it sits a little clearer of the touch
    // HUD (top-bar buttons to the right, scaled systems strip below).
    // Both values are by-eye tuning points.
    override val renderScale = if (PlatformSpecific.INSTANCE.isTouchUi) 1.3f else 1f
    // Desktop: ChoiceBox frames its text at (334,147) (NativeChoiceBox), 150px
    // further left while an enemy ship is present.
    override val windowCentreOffset: ConstPoint
        get() = ConstPoint(
            if (PlatformSpecific.INSTANCE.isTouchUi) -40 else if (game.enemy != null) -155 else -5,
            if (PlatformSpecific.INSTANCE.isTouchUi) -60 else -24
        )

    private val resourceNumFont = game.getFont("JustinFont10")
    private val font = game.getFont("JustinFont11Bold")

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
    private var hoveredOption: Int? = null
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
            : this(game, playerShip, close) {

        // Starting event can be null to get the window open before adding
        // a synthetic event.
        if (startingEvent != null) {
            val created = if (startingEvent.eager) startingEvent
            else game.eventCreator.createFrom(startingEvent.template, game.eventCreator.currentLevel())
            if (created != null) {
                loadEvent(EvaluatedEvent(created, game, null, null))
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

    private fun loadEvent(event: EvaluatedEvent) {
        extraText = ""

        // WorldManager +0x12a0 is cleared when an event starts (0x61b930): the next previews draw a new seed.
        game.eventManager.resetCrewKillSeed()

        // WorldManager::CreateStore (0x613010) is the first thing the original
        // does when it applies an event: the store is drawn before the
        // environment and the ship (whose generation reseeds the stream).
        if (event.event.isStore) {
            game.currentBeacon.hasStore = true
            game.currentBeacon.createStore(game)
        }

        game.loadEventShip(event.created!!)
        event.event.environment?.let { type ->
            val beacon = game.currentBeacon
            val env = if (type == xyz.znix.xftl.sector.Beacon.EnvironmentType.PDS) xyz.znix.xftl.environment.AsbEnvironment(game, beacon, event.event.browserPdsTarget) else type.create(game, beacon)
            beacon.debugSetEnvironment(env)
        }

        if (event.event.isStore) {
            // This does a few things: show the store on the map, enable the store button
            // and cause the store window to open when this window is closed.
            game.currentBeacon.hasStore = true
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
            extraText += "\n\n" + game.translator[messageId]
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

        // Events that have no text are valid, usually they are the result of a choice
        // Eg, to give the player some items and close the menu
        if (event.text == null) {
            // Events that have text and give resources don't add the resources
            // until they're closed. That means the addResources call is made
            // when an option is selected. For these events that'll never happen,
            // so add the resources now.
            addResources(event)

            currentEvent = null

            // Stay open if a synthetic event was just created, we'll close when
            // that's done.
            checkClosed()

            return
        }

        currentEvent = event

        val newOptions = ArrayList<EvaluatedEvent>()
        for (choice in event.created!!.choices)
            newOptions.add(EvaluatedEvent(choice.event, game, choice.template, choice.text))

        // Walk backwards from the end of the options list, removing
        // anything that's not suitable. Aside from iteration order,
        // we specifically need to go backwards to keep the last suitable
        // option among many with max_group set.
        val maxGroupHit = HashSet<Int>()
        for (i in newOptions.size - 1 downTo 0) {
            val option = newOptions[i]
            val choice = option.choice!!

            // If we don't have the blue option requirements, hide the option.
            // This doesn't consider regular resources (missiles, fuel, etc) since
            // if there's not enough of those, the option is merely greyed out.
            if (!hasRequiredEquipment(option)) {
                newOptions.removeAt(i)
                continue
            }

            // If multiple options with the same max_group are set,
            // only the last one (first one we see in this iteration
            // order) is kept.
            if (choice.maxGroup == null)
                continue

            if (maxGroupHit.contains(choice.maxGroup)) {
                newOptions.removeAt(i)
                continue
            }
            maxGroupHit.add(choice.maxGroup)
        }

        options = newOptions

        if (options.isEmpty()) {
            options = listOf(continueEvent)
        }

        optionBoundingBoxes.clear()
    }

    override fun draw(g: Graphics) {
        // Draw the frame. ChoiceBox::OnRender (0x004fc0f0): an outcome box wider than the 602px
        // frame widens it to the box's width + 25, centred on the box.
        val outcome = syntheticEvents.firstOrNull()?.resources ?: currentEvent?.resources
        val outcomeWidth = outcome?.takeIf { it.hasAnything }?.let { layoutOutcome(g, it, 0, 0, null).width } ?: 0
        if (outcomeWidth > size.x) {
            game.windowRenderer.render(position.x - 3 - (outcomeWidth - 577) / 2, position.y, outcomeWidth + 25, size.y)
        } else {
            game.windowRenderer.render(position.x, position.y, size.x, size.y)
        }

        if (syntheticEvents.isNotEmpty()) {
            drawSyntheticEvent(g, syntheticEvents.first())
            return
        }

        // If syntheticEvent is empty, then currentEvent must not be null.
        val event = currentEvent!!

        var textY = drawEventOutcome(g, event.text!! + extraText, event.resources)

        val rebuildBBs = optionBoundingBoxes.isEmpty()

        // You can think of each option having a top and bottom Y. For text
        // events there's a spacing between the top of the space and the baseline
        // of the text (TEXT_OPTION_TOP_OFFSET), and then another offset from
        // the baseline to the bottom of the option (which is the top of the next
        // option) - TEXT_OPTION_BOTTOM_MARGIN.
        // When resources are involved, the top of the resource box sits exactly
        // at the option's Y top, and there's 10 pixels (RESOURCE_BOTTOM_MARGIN)
        // between the bottom of the box and the start of the next option.
        for ((i, option) in options.withIndex()) {
            val choice = option.choice
            val enabled = isConditionsSatisfied(option)
            val colour = when {
                !enabled -> Constants.TEXT_OPTION_DISABLED
                hoveredOption == i -> Constants.TEXT_OPTION_HOVER
                choice == null -> Colour.white
                choice.blue -> Constants.TEXT_OPTION_BLUE
                else -> Colour.white
            }

            val prefix = "${i + 1}. "
            var text = prefix + option.choiceText

            // Handle the 'continue' option, since it fails if
            // we try to read its resources.
            // ChoiceBox lays Continue out like any other text row.
            if (option.isContinue) {
                textY += TEXT_OPTION_TOP_OFFSET
                textY = drawText(textY, text, colour, rebuildBBs)
                textY += TEXT_OPTION_BOTTOM_MARGIN
                continue
            }

            // Warn the user if they'll have to fire a crewmember
            if (option.resources.crew.isNotEmpty() && game.isPlayerCrewFull) {
                text += " " + game.translator["event_crew_full"]
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
                val textTop = textY + (boxSize.y - FONT_HEIGHT) / 2
                val lineY = textTop + FONT_HEIGHT
                val textEndY = drawText(lineY, text, colour, rebuildBBs, maxTextX)

                if (textEndY == lineY) {
                    // The event is only one line long, draw the resource
                    // box after the text.
                    val boxX = textX + font.getWidth(text) + textResourceMargin
                    val boxPos = ConstPoint(boxX, textY)
                    drawResourceBox(g, option.choiceResources, boxPos, boxSize, colour)
                } else {
                    // The text was multiple lines long, draw the resource box
                    // on the right-mode side and centred on the text.
                    // This does leave a notably larger spacing between the line
                    // above as space was made for the box, but that's there in
                    // vanilla FTL too (though slightly shorter - though it's
                    // close enough, and I've had enough of fiddling with
                    // layouts to care about this particular inconsistency).
                    val firstLineTop = lineY - FONT_HEIGHT
                    val textHeight = textEndY - firstLineTop
                    val centreY = firstLineTop + textHeight / 2 - boxSize.y / 2
                    val boxPos = ConstPoint(maxBoxX, centreY)
                    drawResourceBox(g, option.choiceResources, boxPos, boxSize, colour)
                }

                textY = max(textEndY + TEXT_OPTION_BOTTOM_MARGIN, boxSize.y + RESOURCE_BOTTOM_MARGIN)
            } else {
                textY += TEXT_OPTION_TOP_OFFSET
                textY = drawText(textY, text, colour, rebuildBBs)
                textY += TEXT_OPTION_BOTTOM_MARGIN
            }
        }
    }

    private fun drawEventOutcome(g: Graphics, text: String, resourcesGained: ResourceSet): Int {
        var textY = position.y + 35
        textY = drawText(textY, text)

        if (resourcesGained.hasAnything) {
            // The box is centred 43 pixels below the baseline of the last line of
            // the event text.
            val boxMiddleY = textY + 43

            drawOutcomeBox(g, resourcesGained, position.x + 295, boxMiddleY)

            // The spacing between the event text and the options is constant, regardless
            // of the size of the rewards.
            textY += 95
        } else {
            textY += 45
        }

        // At this point, textY is where the choices should go.

        return textY
    }

    private fun drawSyntheticEvent(g: Graphics, event: SyntheticEvent) {
        val textY = drawEventOutcome(g, event.text, event.resources)

        val rebuildBBs = optionBoundingBoxes.isEmpty()

        // Draw the continue option
        val colour = when (hoveredOption) {
            0 -> Constants.TEXT_OPTION_HOVER
            else -> Colour.white
        }

        val text = "1. " + game.translator["continue"]
        drawText(textY, text, colour, rebuildBBs)
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
            val w = image.height
            val h = image.width
            val name = game.translator[bp.title!!]
            if (draw) {
                bp.drawLauncherUI(image, g!!, (x + cx + w / 2 - (w + 1) / 2).f, (y + 16 - h / 2).f)
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

        for (line in font.wrapString(msg, maxWidth.toInt())) {
            longestLine = longestLine.coerceAtLeast(font.getWidth(line))
            font.drawString(textX.f, textY.f, line, colour)
            textY += 20
        }

        // Remove the space used by the last line.
        textY -= 20

        if (addOption) {
            optionBoundingBoxes += Rectangle(textX.f, y.f - 11, longestLine.f, textY - y + 14f)
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

        val res = event.resources

        // Check we have enough stuff, if any of the resources are negative
        if (playerShip.scrap < -res.scrap)
            return false
        if (playerShip.missilesCount < -res.missiles)
            return false
        if (playerShip.dronesCount < -res.droneParts)
            return false
        if (playerShip.fuelCount < -res.fuel)
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

    private fun checkReq(choice: Choice): Boolean {
        val req = choice.req ?: return true

        // Req is the requirement name of an event choice. Per Choice.req, it is:
        // "any race, weapon, drone, augmentation or system/subsystem"

        // Check for matching races
        if (requiredCrewCount(req) != null) return crewRequirementSatisfied(choice)

        // Check for matching weapons, drones and augments. We search through all
        // the places a blueprint could be, but none of these checks are specific
        // to that type of item. For example, we could find a weapon in the cargo
        // hold just the same as if it's in the weapons slots.
        fun hasBlueprint(name: String): Boolean {
            if (playerShip.hardpoints.any { it.weapon?.type?.name == name }) {
                return true
            }
            if (playerShip.drones?.drones?.any { it?.type?.name == name } == true) {
                return true
            }
            if (playerShip.augmentValues.any { it.key.name == name }) {
                return true
            }
            return playerShip.cargoBlueprints.any { it?.name == name }
        }
        if (hasBlueprint(req))
            return true

        // Req can be a blueprint list, in which case match anything from that.
        // For example, DISTRESS_SATELLITE_DEFENSE's ion weapon option.
        val bpList = game.blueprintManager.blueprints[req] as? BlueprintList
        if (bpList != null) {
            for (bp in bpList.list()) {
                if (hasBlueprint(bp.name))
                    return true
            }
        }

        // Check for any matching systems
        val matchingSystem = playerShip.systems.firstOrNull { it.codename == req }
        if (matchingSystem != null) {
            // Check if we're outside the level bounds of this system
            choice.minLevel?.let { minLevel ->
                if (matchingSystem.energyLevels < minLevel)
                    return false
            }
            choice.maxLevel?.let { maxLevel ->
                if (maxLevel < matchingSystem.energyLevels)
                    return false
            }

            // Note that choice.maxGroup is processed during event setup.

            return true
        }

        // If the system is 'reactor', we just have to check it's
        // between the minimum and maximum level.
        if (req == "reactor") {
            choice.minLevel?.let { minLevel ->
                if (playerShip.purchasedReactorPower < minLevel)
                    return false
            }
            choice.maxLevel?.let { maxLevel ->
                if (maxLevel < playerShip.purchasedReactorPower)
                    return false
            }
            return true
        }

        return false
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
        for ((i, bb) in optionBoundingBoxes.withIndex()) {
            if (bb.contains(p.x.f, p.y.f)) {
                hoveredOption = i
            }
        }
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON) return
        updateUI(x, y)
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

    fun selectOption(idx: Int) {
        // Pressing continue on a synthetic event closes it
        val syntheticEvent = syntheticEvents.firstOrNull()
        if (syntheticEvent != null) {
            if (idx == 0) {
                syntheticEvents.removeAt(0)

                game.givePlayerResources(syntheticEvent.resources)

                // Re-calculate the bounding boxes now the event is visible again
                optionBoundingBoxes.clear()

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

        loadEvent(choice)
    }

    fun addSyntheticEvent(syntheticEvent: SyntheticEvent) {
        syntheticEvents.add(syntheticEvent)

        // Re-calculate the bounding boxes to only show the continue option.
        optionBoundingBoxes.clear()
    }

    private fun addResources(event: EvaluatedEvent) {
        game.givePlayerResources(event.resources)

        // Remove blueprints
        removeStuff@ for (name in event.event.removedStuff) {
            val blueprint = game.blueprintManager[name]
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

        // Apply the status effects: WorldManager 0x613a10 / 0x613760 apply each entry to the player's ship when its
        // target is the player or both, then to every enemy ship when it is the enemy or both. The ship system keeps
        // three independent fields (ShipSystem 0x63d1a0..0x63d3e0): the limit (+0x11c), the loss (+0x120) and the
        // divisor (+0x124, which also sets the limit to half the level). Clear resets all three.
        for (effect in event.event.statuses) {
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

        if (event.event.revealMap) {
            game.currentBeacon.sector.mapRevealed = true
        }
        if (event.event.secretSector) secretSectorPending = true
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
            else -> system!!.energyLevels >= system.blueprint.maxPower
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

        close()
        if (secretSectorPending) {
            secretSectorPending = false
            game.browserEnterSecretSector()
        }
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        elem.setAttribute("secretSectorPending", secretSectorPending.toString())
        elem.setAttribute("openTime", openTime.toString())
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
            val choice = parent!!.created!!.choices[index]
            return EvaluatedEvent(choice.event, game, choice.template, choice.text)
        }

        val createdElem = elem.getChild("created")
        if (createdElem != null) {
            val created = CreatedEvent.loadFromXML(createdElem, game)
            val choiceId = SaveUtil.getAttr(elem, "choiceId")
            val choice = if (choiceId == "") null else game.eventManager.getChoiceByDeserialisationId(choiceId)
            return EvaluatedEvent(created, game, choice, null)
        }

        // The format before events were created eagerly: the event and
        // choice ids and a seed. The event is created now instead.
        val eventId = SaveUtil.getAttr(elem, "eventId")
        val event = game.eventManager.getByDeserialisationId(eventId)
        val choiceId = SaveUtil.getAttr(elem, "choiceId")
        val choice = if (choiceId == "") null else game.eventManager.getChoiceByDeserialisationId(choiceId)
        val created = game.eventCreator.createFrom(event, game.eventCreator.currentLevel())
            ?: return continueEvent
        return EvaluatedEvent(created, game, choice, null)
    }

    /**
     * Represents an event that doesn't appear in XML, but is inserted by
     * code when something happens. This includes breaking down duplicate
     * augments, and the crew clone success/failure messages.
     */
    class SyntheticEvent(val text: String) {
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
        val resources by lazy { created!!.toResourceSet(game) }
        val text: String? = created?.text?.localised

        /**
         * If this event is being displayed as a choice inside another event,
         * this is the text it's option uses.
         */
        val choiceText: String = choiceTextDrawn?.localised ?: game.translator["continue"]

        /**
         * The resources that should be displayed in a choice. This filters out
         * crew losses, hidden items, and anything else that shouldn't show up.
         */
        val choiceResources: ResourceSet by lazy {
            val result = ResourceSet()

            // If hidden is set, don't show any resources.
            if (choice?.hidden == true)
                return@lazy result

            result += resources
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

            return@lazy result
        }
    }

    companion object {
        private const val FONT_HEIGHT = 11

        // Text-only options (no resources) are spaced 32 pixels apart
        private const val TEXT_OPTION_SPACING = 32

        // The pixels between the top of an option and where it's text is placed
        private const val TEXT_OPTION_TOP_OFFSET = 15
        private const val TEXT_OPTION_BOTTOM_MARGIN = TEXT_OPTION_SPACING - TEXT_OPTION_TOP_OFFSET

        private const val RESOURCE_BOTTOM_MARGIN = 10

        private const val RESOURCE_LEFT_START = 5
        private const val RESOURCE_ICON_SPACING = 30
        private const val RESOURCE_RIGHT_SPACING = 9
    }
}
