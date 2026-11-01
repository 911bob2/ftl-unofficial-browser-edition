package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.ClonebayHealing
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.GlowColour
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.game.WarningFlasher
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.max
import kotlin.math.min

class Clonebay(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.CLONEBAY

    val queue = ArrayList<LivingCrew>()

    var cloneProgress: Float = 0f
    var dyingProgress: Float = 0f

    // Hyperspace CloneBox_Extend (enhancedCloneUI, hs/crew/HsCloneQueue.kt): the first shown queue entry, the
    // page buttons, the hovered entry, the crew member carried by a right press and the last mouse position.
    @JvmField var hsDisplayShift = 0
    @JvmField var hsPageDown = false
    @JvmField var hsPageUp = false
    @JvmField var hsPageDownHover = false
    @JvmField var hsPageUpHover = false
    @JvmField var hsHoverCrew = -1
    @JvmField var hsSelectedCrew: LivingCrew? = null
    @JvmField var hsMouseX = 0
    @JvmField var hsMouseY = 0

    // Hyperspace Lua CloneSystem members (native 1.6.14 CloneSystem 0x644a20/OnLoop 0x645780): `clone` (+0x1c4) is the
    // crew member the last finished cloning brought back; `bottom`/`top`/`gas` (+0x1d0..) the tub textures, replaced
    // from Lua; `slot` (+0x1dc) the tub's slot. Null/unset means the engine's own.
    @JvmField var hsLastClone: LivingCrew? = null
    @JvmField var hsBottom: xyz.znix.xftl.hs.lua.LuaTexture? = null
    @JvmField var hsTop: xyz.znix.xftl.hs.lua.LuaTexture? = null
    @JvmField var hsGas: xyz.znix.xftl.hs.lua.LuaTexture? = null
    @JvmField var hsSlotOverride: Int? = null

    /** CloneSystem::slot: the tub's slot in the room, -1 without one. */
    val hsSlot: Int get() = hsSlotOverride ?: equipmentLocation?.let { room!!.pointToSlot(it) } ?: -1

    /**
     * CloneSystem::fTimeToClone: the seconds of cloning so far; 0 with nobody queued or while unpowered (OnLoop
     * 0x645780 writes -1 and then 0 for an empty queue).
     */
    var hsTimeToClone: Float
        get() = if (queue.isEmpty() || !isEnabled) 0f else cloneProgress * cloneDuration
        set(v) { cloneProgress = (v / cloneDuration).coerceAtLeast(0f) }

    /** CloneSystem::fDeathTime: -1 unless the queue is dying, else the seconds of the 3 s death so far. */
    var hsDeathTime: Float
        get() = if (queue.isEmpty() || !isDying) -1f else dyingProgress * 3f
        set(v) { dyingProgress = (v / 3f).coerceAtLeast(0f) }

    internal val cloneDuration: Float
        get() {
            return CLONE_DURATIONS.getOrNull(powerSupplied - 1) ?: CLONE_DURATIONS[0]
        }

    private val isEnabled: Boolean get() = powerSupplied > 0 && !isHackActive
    private val isDying: Boolean get() = !isEnabled && !hasBackupDNA

    private val hasBackupDNA: Boolean get() = ship.hasAugment(AugmentBlueprint.BACKUP_DNA)

    /**
     * The location of the cloning tub.
     *
     * This can be null on some ships, for example CIRCLE_SCOUT_DLC.
     *
     * In those cases, the cloning tub is hidden.
     */
    private val equipmentLocation: RoomPoint? by lazy {
        val obstruction = room!!.obstructions.firstOrNull() ?: return@lazy null
        RoomPoint(room!!, obstruction)
    }

    private val bottomImage by onInit { it.getImg("img/ship/interior/clone_bottom.png") }
    private val gasImage by onInit { it.getImg("img/ship/interior/clone_gas.png") }
    private val topImage by onInit { it.getImg("img/ship/interior/clone_top.png") }

    private val soloBarImage by onInit { it.getImg("img/systemUI/clonebar_small_solo.png") }
    private val bottomBarImage by onInit { it.getImg("img/systemUI/clonebar_small_bottom.png") }
    private val topBarImage by onInit { it.getImg("img/systemUI/clonebar_small_top.png") }
    private val middleBarImage by onInit { it.getImg("img/systemUI/clonebar_small_middle.png") }
    private val fillImage by onInit { it.getImg("img/systemUI/clonebar_small_fill.png") }

    private val cloneSound by onInit { it.sounds.getSample("cloneArrive") }
    private val offSound by onInit { it.sounds.getLoop("cloneBroken") }

    // Fonts can't be loaded in automated tests (no GL context) - it's only
    // needed when drawing the "+N" queue count, so look it up on use.
    private val hiddenCrewFont: SILFontLoader?
        get() = if (ship.sys.isRunningAutomatedTest) null else ship.sys.getFont("JustinFont10")

    override fun update(dt: Float) {
        super.update(dt)

        if (queue.isEmpty()) {
            cloneProgress = 0f
            dyingProgress = 0f
            return
        }

        if (isEnabled) {
            // Use a 0-1 progress instead of a timer, so changing
            // the power doesn't adjust your progress.
            cloneProgress += hsTime(dt, true) / cloneDuration
            // Hyperspace CloneSystem::OnLoop (CustomCrew.cpp): the first queued crew member's cloneSpeedMultiplier
            if (xyz.znix.xftl.hs.HS.active) cloneProgress += xyz.znix.xftl.hs.crew.HsCrewEffects.cloneSpeedExtra(queue.firstOrNull(), dt) / cloneDuration

            if (cloneProgress >= 1f) {
                cloneFinished()
            }
        } else {
            cloneProgress = 0f
        }

        if (isDying) {
            dyingProgress += hsTime(dt, false) / 3f

            if (dyingProgress > 1f) {
                killCrew()
            }

            // Play the suitably nasty crew dying sound
            offSound.continueLoopPlayerOnly(ship)
        } else {
            dyingProgress = 0f
        }
    }

    /**
     * Hyperspace: CloneSystem::OnLoop runs at the room's temporal dilation, and the clone/death speeds follow the
     * hyperspace.xml clone levels ([xyz.znix.xftl.hs.systems.HsClone]). Vanilla: [dt] unchanged.
     */
    private fun hsTime(dt: Float, cloning: Boolean): Float {
        if (!xyz.znix.xftl.hs.HS.active) return dt
        return dt * xyz.znix.xftl.hs.systems.HsTemporal.factor(room) *
                xyz.znix.xftl.hs.systems.HsClone.speedMultiplier(this, cloning)
    }

    override fun onJump() {
        super.onJump()

        // Hyperspace: CloneHealing by the hyperspace.xml clone levels.
        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.hs.systems.HsClone.jumpHeal(this)
            return
        }

        // Heal our crew each jump.
        if (undamagedEnergy == 0)
            return

        val healing = PASSIVE_HEALING[undamagedEnergy - 1]

        for (crew in ship.crew) {
            // Skip drones
            if (crew !is LivingCrew)
                continue

            // Skip boarders
            if (crew.ownerShip != ship)
                continue

            crew.dealDamage(ClonebayHealing(-healing.f))
        }
    }

    /** The cloning tub, with the room's floor stage (under the icon, which comes after the walls). */
    override fun drawRoomFloor(g: Graphics) {
        drawEquipment()
    }

    private fun drawEquipment() {
        // Don't draw anything if there's no computer location set in the XML.
        val location = equipmentLocation ?: return

        val x = location.offsetX
        val y = location.offsetY

        (hsBottom?.image ?: bottomImage).draw(x, y)

        val currentCrew = queue.firstOrNull()
        if (currentCrew != null) {
            (hsGas?.image ?: gasImage).draw(x, y)
        }

        (hsTop?.image ?: topImage).draw(x, y)
    }

    override fun makeExtraButtons(powerPos: IPoint): List<xyz.znix.xftl.game.Button> {
        if (!xyz.znix.xftl.hs.HS.active) return emptyList()
        return listOf(xyz.znix.xftl.hs.crew.HsCloneQueue.QueueButton(ship.sys, this, powerPos))
    }

    override fun drawIconAndPower(
        game: InGameState,
        g: Graphics,
        isPlayer: Boolean,
        drawPower: Boolean,
        hoverGlow: Boolean,
        x: Int,
        y: Int
    ) {
        super.drawIconAndPower(game, g, isPlayer, drawPower, hoverGlow, x, y)

        if (!isPlayer)
            return

        drawOffWarning(game, g, x - 19, y - 11 - (energyLevels - 1) * 8)

        // The queue goes on top of the top energy bar. Its bottom is that of the contents area
        // of the boxes, not including the white outline.
        drawQueueRows(x - 7 - 6, y - 11 - (energyLevels - 1) * 8 - 4 - 3)

        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.hs.crew.HsCloneQueue.renderButtons(game, this, x, y)
            xyz.znix.xftl.hs.crew.HsCloneQueue.renderCarried(this, soloBarImage)
        }
    }

    /** The touch layout's small queue over a closed box, with its bay-off warning. */
    internal fun touchDrawQueue(game: InGameState, g: Graphics, queueX: Int, bottomY: Int, boxX: Int, topBarTop: Int) {
        drawQueueRows(queueX, bottomY)
        drawOffWarning(game, g, boxX, topBarTop)
    }

    /** The touch layout's large queue rows in an open box: [centreY] is the middle of the bottom row, rows go up by 46. */
    internal fun touchDrawLargeQueue(game: InGameState, g: Graphics, centreX: Int, centreY: Int) {
        val fill = game.getImg("img/ipad/systemUI/clonebar_large_fill.png")
        val shift = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCloneQueue.displayShift(this) else 0
        val rows = min(queue.size, 3)
        for (index in 0 until rows) {
            val crewIdx = shift + index
            val cy = centreY - 46 * index
            val left = centreX - 31
            if (crewIdx == 0 && isEnabled) {
                val h = (cloneProgress * 40).toInt()
                fill.draw(left.f, cy + 20f - h, left + 62f, cy + 20f, 0f, 40f - h, 62f, 40f, 1f, Colour.green)
            }
            if (index == 2 && crewIdx < queue.size - 1) {
                xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 24)
                    .printCenter(centreX.f, cy - 24f, "+" + (queue.size - 2), Colour.white)
            } else {
                g.pushTransform()
                g.translate(left.f, cy - 30f)
                g.scale(2f, 2f)
                queue[crewIdx].drawPortrait(0, 0, false)
                g.popTransform()
            }
            if (index == rows - 1 && isDying) {
                val h = (dyingProgress * 40).toInt().f
                for (i in 1..2) fill.draw(left.f, cy - 20f, left + 62f, cy - 20f + h, 0f, 0f, 62f, h, 1f, Constants.CLONE_DYING_FILTER)
            }
        }
    }

    private fun drawQueueRows(queueX: Int, startBottomY: Int) {
        var queueBottomY = startBottomY

        // Only show up to three bars with cloning crew in them.
        // If we have four or more crew in the queue, the top box
        // has a '+x' in it to show the number of hidden crew.
        val barCount = min(queue.size, 3)
        // Hyperspace (CloneQueue.cpp CloneBox::OnRender): the queue shows three entries from displayShift.
        val shift = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCloneQueue.displayShift(this) else 0

        // Draw the crew icons/progress bar
        for (index in 0 until barCount) {
            val crewIdx = shift + index
            val img = when {
                barCount == 1 -> soloBarImage
                index == 0 -> bottomBarImage
                index == barCount - 1 -> topBarImage
                else -> middleBarImage
            }

            // The Y of the bottom of the contents area, relative to the top of the image.
            val imageBottomOffset = when (index) {
                barCount - 1 -> 29
                else -> 23
            }

            img.draw(queueX, queueBottomY - imageBottomOffset)

            // Find the bounds of the window in which the crewmember is shown.
            // This is used for animating the cloning/dying bar.
            val windowWidth = 34
            val windowHeight = 20
            val windowX = queueX + 9
            val windowY = queueBottomY - windowHeight

            // Draw the progress bar
            if (crewIdx == 0 && isEnabled) {
                val fillTopHeight = windowHeight - (cloneProgress * windowHeight).toInt()
                fillImage.draw(
                    windowX.f, windowY + fillTopHeight.f,
                    windowX.f + windowWidth, queueBottomY.f,

                    0f, fillTopHeight.f,
                    windowWidth.f, windowHeight.f,

                    1f, Colour.green
                )
            }

            // Crew are drawn over the progress bar, but under the dying bar.
            if (index == 2 && crewIdx < queue.size - 1) {
                // Draw the number of hidden crew
                val shownCrew = barCount - 1 // -1 because this is taking up a slot
                val hiddenCrew = queue.size - shownCrew
                val numStr = "+$hiddenCrew"

                hiddenCrewFont?.drawString(windowX + 11f, windowY + 15f, numStr, Colour.white)
            } else if (queue[crewIdx] !== hsSelectedCrew) {
                // Draw the crewmember portrait (Hyperspace: not the one carried by the mouse)
                val crew = queue[crewIdx]
                crew.drawPortrait(windowX, windowY - 5, false)
            }

            // Draw the crew dying bar
            if (index == barCount - 1 && isDying) {
                val fillTopHeight = (dyingProgress * windowHeight).toInt()

                // Draw it twice to get the right colours
                for (i in 1..2) {
                    fillImage.draw(
                        windowX.f, windowY.f,
                        windowX.f + windowWidth, windowY.f + fillTopHeight,

                        0f, 0f,
                        windowWidth.f, fillTopHeight.f,

                        1f, Constants.CLONE_DYING_FILTER
                    )
                }
            }

            // This is the height of the blank area, plus the height of the
            // outline bar that sits atop it.
            queueBottomY -= 20 + 3
        }
    }

    private var offWarning: WarningFlasher? = null

    /**
     * CloneBox::OnRender (0x004ba720): CLONEBAY OFF / CREW ARE DYING!, a looping WarningWithLines (made by the
     * box's constructor, 0x004b8cb0) shown while dead crew wait, the bay doesn't work and there's no BACKUP_DNA.
     * It hangs off the top of the box's stack (the bar render's result, 0x0063c310): the top bar less the
     * queue's 23 px per shown clone plus 6, any ion or hacking lock, and 20. From there (box x, that top) the
     * line runs (32, 16) - (32, -34) - (195, -34); the first text's top is at (129, -55), centred, and the
     * second sits 23 lower, not left of the line.
     */
    private fun drawOffWarning(game: InGameState, g: Graphics, boxX: Int, topBarTop: Int) {
        val on = queue.isNotEmpty() && isDying
        if (!on && offWarning == null)
            return
        val warning = offWarning ?: WarningFlasher(
            game, ConstPoint.ZERO, "warning_clone_off_1", warningWarning = false
        ).also { offWarning = it }
        warning.loopWhile(on)
        if (!warning.isRunning)
            return

        var top = topBarTop - (min(queue.size, 3) * 23 + 6) - 20
        if (xyz.znix.xftl.hs.HS.active) top -= xyz.znix.xftl.hs.crew.HsCloneQueue.extraHeight(this)
        if (isIonised || hasWhiteLockingBox)
            top -= 24
        if (isHackActive)
            top -= 22

        warning.placeNative(boxX + 129, top - 55)
        warning.linePoints = listOf(
            ConstPoint(boxX + 32, top + 16), ConstPoint(boxX + 32, top - 34), ConstPoint(boxX + 195, top - 34)
        )
        warning.draw(g)

        val font = game.getFont("HL1", 2f)
        val text = game.translator["warning_clone_off_2"]
        val left = max(boxX + 129 - font.getWidth(text) / 2, boxX + 32)
        val alpha = if (warning.isFlashingHigh) 0.9f else 0.4f
        UIUtils.drawStringWithGlow(game, font, text, left, top - 32 + 16, GlowColour.RED, alpha)
    }

    fun addDeadCrew(crew: LivingCrew) {
        queue += crew
    }

    private fun cloneFinished() {
        cloneProgress = 0f

        cloneSound.play()

        if (ship.sys.debugFlags.noClone.set)
            return

        val freeSpace = ship.findSpaceForCrew(room!!, AbstractCrew.SlotType.CREW)

        val crew = queue.removeAt(0)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCloneQueue.removed(this, crew)
        // Hyperspace CREW_CLONE (CrewMember::Clone, priority -9999): before the skill loss and the clone.
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.crewClone(crew)
        val hsSkills = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsClone.skillsBefore(crew) else null
        crew.onCloned()
        // Hyperspace: CrewMember::Clone hook (clone health and skill loss by the clone levels).
        if (hsSkills != null) xyz.znix.xftl.hs.systems.HsClone.afterClone(this, crew, hsSkills)
        ship.crew.add(crew)
        crew.jumpTo(freeSpace)
        hsLastClone = crew
    }

    private fun killCrew() {
        dyingProgress = 0f

        if (ship.sys.debugFlags.noClone.set)
            return

        // Kill crew in the opposite order they are cloned in.
        val dead = queue.removeAt(queue.size - 1)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCloneQueue.removed(this, dead)
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addTagFloat(elem, "cloneProgress", cloneProgress, 0f)
        SaveUtil.addTagFloat(elem, "dyingProgress", dyingProgress, 0f)

        val crewListElem = Element("cloningQueue")
        for (crew in queue) {
            refs.register(crew, "cloningCrew")

            val crewElem = Element("crewMember")
            crew.saveToXML(crewElem, refs)
            crewListElem.addContent(crewElem)
        }
        elem.addContent(crewListElem)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        cloneProgress = SaveUtil.getOptionalTagFloat(elem, "cloneProgress") ?: 0f
        dyingProgress = SaveUtil.getOptionalTagFloat(elem, "dyingProgress") ?: 0f

        for (crewElem in elem.getChild("cloningQueue").getChildren("crewMember")) {
            val race = crewElem.getAttributeValue("type")
            val blueprint = ship.sys.blueprintManager.blueprints[race]
                ?: error("Missing crew blueprint for race '$race' (while loading clonebay queue)")
            require(blueprint is CrewBlueprint)

            val crew = blueprint.spawn(room!!, AbstractCrew.SlotType.CREW)
            crew.loadFromXML(crewElem, refs)
            queue.add(crew)
        }
    }

    companion object {
        val INFO: SystemInfo = ClonebayInfo

        val CLONE_DURATIONS = listOf(12f, 9f, 7f, 0.001f)

        /**
         * The clone duration in seconds for a 1-based system power level.
         * Used by the vanilla-format save writer.
         */
        fun durationForLevel(level: Int): Float =
            CLONE_DURATIONS.getOrNull(level - 1) ?: CLONE_DURATIONS[0]

        val durationCount: Int get() = CLONE_DURATIONS.size
        val PASSIVE_HEALING = listOf(8, 16, 25, 65536)
    }
}

private object ClonebayInfo : SystemInfo("clonebay") {
    override val canBeManned: Boolean get() = false

    override val isComputerObstruction: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Clonebay(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        if (xyz.znix.xftl.hs.HS.active) {
            // SystemDescriptionFixes (HS 1.23.2): the fourth level reads clonebay_instant_fullheal.
            if (level == 3) return translator["clonebay_instant_fullheal"]
            return xyz.znix.xftl.hs.systems.HsClone.levelName(level, translator["clone_full"])
        }
        val time = Clonebay.CLONE_DURATIONS[level].toInt()
        val heal = Clonebay.PASSIVE_HEALING[level]
        return translator["clone_full"].replaceArg(time).replaceArg(heal, 2)
    }
}
