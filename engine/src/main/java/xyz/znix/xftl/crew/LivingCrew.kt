package xyz.znix.xftl.crew

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.PlayerShipUI
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.*
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Represents a crew member that can be hired by the player.
 */
abstract class LivingCrew(blueprint: CrewBlueprint, anims: Animations, room: Room, mode: SlotType) :
    AbstractCrew(blueprint, anims, room) {

    override val repairSpeed: Float get() = 1f + REPAIR_SKILL_BONUS[getSkillLevel(Skill.REPAIRS).ordinal] / 100f

    override val attackDamageMult: Float
        get() = (1f + COMBAT_SKILL_BONUS[getSkillLevel(Skill.COMBAT).ordinal] / 100f) * mindControlDamageBoost

    override val maxHealth: Float get() = baseMaxHealth + mindControlHealthBoost

    /** MindSystem's health boost on this crewmember (CrewMember+0x6e8). */
    var mindControlHealthBoost: Float = 0f
        private set

    /** MindSystem's damage boost on this crewmember (SetDamageBoost). */
    var mindControlDamageBoost: Float = 1f
        private set

    /**
     * CrewMember's health boost setter (0x47b870): a larger boost raises
     * the current health with the maximum, a smaller one only clamps it.
     */
    fun setMindControlBoost(healthBoost: Float, damageBoost: Float) {
        mindControlDamageBoost = damageBoost
        val delta = healthBoost - mindControlHealthBoost
        if (delta == 0f)
            return
        mindControlHealthBoost = healthBoost
        onHealthBoostChanged(delta)
        health = if (delta > 0f) health + delta else min(health, maxHealth)
    }

    /** Hyperspace's CrewMember::SetHealthBoost keeps its own health maximum (health.second) up to date. */
    protected open fun onHealthBoostChanged(delta: Float) {}

    /** Restores a saved boost; the saved health already includes it. */
    fun restoreMindControlHealthBoost(healthBoost: Float) {
        mindControlHealthBoost = healthBoost
    }

    open val isMindControlResistant: Boolean get() = false

    // A placeholder until the creator sets the real one: making a crew member draws nothing from the generation
    // stream by itself (ShipManager 0x4a24a0 draws the name, timer and colours, which the callers make).
    var info: LivingCrewInfo = LivingCrewInfo(room.ship.sys, blueprint, "", 0)
        set(value) {
            field = value
            onInfoAssigned()
        }

    /** Called when the creator (or a save) sets [info]. */
    protected open fun onInfoAssigned() {}

    /**
     * The ship that 'owns' this crewmember, or null if they were
     * spawned in by an intruder event.
     */
    var ownerShip: Ship? = if (mode == SlotType.CREW) room.ship else null
    private val browserStrandedAugments = HashMap<String, Float>()

    override val standingShipHostile: Boolean
        get() {
            // A mind-controlled crewmember has its allegiance flipped (see
            // [mode]). Whenever that makes it an intruder on the ship it's
            // standing on, it is at war with that ship - fighting its crew
            // and sabotaging its systems. Without this the mind-controlled
            // crew just stands around, too loyal to wreck anything.
            if (isMindControlled)
                return mode == SlotType.INTRUDER

            // Event-spawned boarders have no owner ship, and are hostile
            // to the player's ship regardless of any ongoing fights.
            val owner = ownerShip ?: return true

            // Are we at war with the ship we're standing on?
            if (game.getEnemyOf(room.ship) == owner)
                return true

            // If the ship that deployed us is gone (destroyed or jumped
            // away), we're stranded - keep fighting regardless.
            return !game.isShipPresent(owner)
        }

    /**
     * The mind control system that is actively controlling this crewmember.
     */
    var mindControlledBy: MindControl? = null

    /**
     * Hyperspace: CrewMember::bMindControlled (+0x53d) as CrewMember::SetMindControl sets it. Under HS the mind
     * system keeps its own list (MindSystem::controlledCrew) and MV's mind-control weapons set the flag from Lua with no
     * system at all, so the flag is the crew member's own; [mindControlledBy] stays null there. Always false in vanilla.
     */
    @JvmField var hsMindControlled = false

    /** CrewMember::bMindControlled: controlled by a mind system (vanilla) or flagged by SetMindControl (Hyperspace). */
    val isMindControlled: Boolean get() = mindControlledBy != null || hsMindControlled

    private var mindControlAnimation: FTLAnimation? = null

    /**
     * The skill this crewmember last gained experience in, for as long
     * as the experience indicator on their crew box is shown.
     *
     * This is deliberately not serialised - it's transient UI feedback.
     */
    var lastTrainedSkill: Skill? = null
        private set

    private var skillIndicatorTimer = 0f

    /**
     * CrewMember::levelUp (an AnimationTracker of [LEVEL_UP_TIME]) and lastLevelUp: CrewMember::
     * IncreaseSkill starts it, plays levelup and sets lastLevelUp when a skill reaches its half or its full
     * experience; OnRenderHealth draws the skill's icon growing and fading out over the crew while it runs.
     */
    @JvmField var levelUpTime = 0f
    @JvmField var levelUpRunning = false
    @JvmField var lastLevelUp: Skill? = null

    override val playerControllable: Boolean
        get() = !isMindControlled && ownerShip?.isPlayerShip == true

    override val showRedOutline: Boolean get() = ownerShip?.isPlayerShip != true

    override val belongsToPlayer: Boolean get() = ownerShip?.isPlayerShip == true

    // If a crewmember is mind controlled, you have vision of it whether
    // it belongs to the player or not.
    override val providesPlayerVision: Boolean
        get() = ownerShip?.isPlayerShip == true || isMindControlled

    // Mantis Pheromones (CREW_STIMS): +value movement speed for this
    // crewmember's home-ship crew, applying both on their own ship and
    // while boarding (CrewMember::GetMoveSpeed 0x47b450: 1 + the summed
    // augment value of the crew's own ship when CanStim 0x775cc0 allows it,
    // which skips crew drones).
    override val augmentSpeedMult: Float
        get() = 1f + (getAugmentValue(AugmentBlueprint.CREW_STIMS) ?: 0f)

    @Suppress("SENSELESS_COMPARISON")
    override val backImg: Image?
        get() = when {
            // This seems unnecessary since info is non-nullable, but it's read
            // in the AbstractCrew constructor.
            info == null -> super.backImg

            info.isFemale -> game.getImg("img/people/female_color.png")
            else -> super.backImg
        }

    override val mode: SlotType
        get() {
            var side = when (room.ship) {
                ownerShip -> SlotType.CREW
                else -> SlotType.INTRUDER
            }

            if (isMindControlled) {
                side = side.other
            }

            return side
        }

    override val suffocationMultiplier: Float
        get() {
            getAugmentValue(AugmentBlueprint.OXYGEN_MASKS)?.let { return it }
            return 1f
        }

    override fun update(dt: Float) {
        // Update the mind-control status now, so it applies for
        // the rest of the update cycle.
        val controller = mindControlledBy
        if (xyz.znix.xftl.hs.HS.active) {
            // Hyperspace: the mind system's own loop (MindSystem::OnLoop, CustomSystems.cpp) releases its crew and sets
            // the boosts of the ones it holds; a crew member that is not mind controlled has none.
            mindControlledBy = null
            if (!hsMindControlled) {
                mindControlAnimation = null
                setMindControlBoost(0f, 1f)
            } else {
                mindControlAnimation?.update(dt)
            }
        } else if (controller?.isControlling(this) != true) {
            mindControlledBy = null
            mindControlAnimation = null
            setMindControlBoost(0f, 1f)
        } else {
            mindControlAnimation?.update(dt)
            setMindControlBoost(controller.healthBoost, controller.damageBoost)
        }

        super.update(dt)
        if (ownerShip === game.player && !game.isCurrentlyLoadingSave)
            game.mainGame?.profile?.updateCrewRecords(info)

        // AnimationTracker::Update of levelUp (CrewMember::OnLoop): game time; at its end it is done and stopped
        // (AnimationTracker::Stop(false) puts current_time back to 0; oracle sw4-skill: 0, not running, done).
        // Hyperspace scripts reach it as CrewMember.levelUp (their writes taken first, the result handed back)
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaAdaptersSweepUi.pullLevelUp(this)
        if (levelUpRunning) {
            levelUpTime += dt
            if (levelUpTime >= LEVEL_UP_TIME) {
                levelUpTime = 0f
                levelUpRunning = false
            }
        }
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaAdaptersSweepUi.pushLevelUp(this)

        // Countdown how long the experience indicator stays on this
        // crewmember's crew box after their last point of experience.
        if (skillIndicatorTimer > 0f) {
            skillIndicatorTimer -= dt
            if (skillIndicatorTimer <= 0f) {
                skillIndicatorTimer = 0f
                lastTrainedSkill = null
            }
        }
    }

    override fun drawForeground(g: Graphics) {
        super.drawForeground(g)
        drawLevelUp()

        if (!isMindControlled || stunTime > 0f)
            return

        // Draw the mind-control animation
        if (mindControlAnimation == null) {
            mindControlAnimation = game.animations["mindcontrol"].startLooping(game)
        }

        val filter = when (showRedOutline) {
            true -> Colour.red
            false -> Colour.green
        }

        if (showHealthBar) {
            // Draw to the left of the health bar
            mindControlAnimation!!.draw(screenX.f - 11, screenY.f - 3, filter)
        } else {
            // Draw in the centre, above the crew
            mindControlAnimation!!.draw(screenX.f + 9, screenY.f, filter)
        }
    }

    override fun drawImage(x0: Float, y0: Float, x1: Float, y1: Float, baseFrame: Image, alpha: Float) {
        info.drawImage(x0, y0, x1, y1, baseFrame, alpha, true)
    }

    /**
     * CrewMember::OnRenderHealth's level-up (Hyperspace's rewrite, CustomCrew.cpp, as the original): for the player's crew
     * while levelUp runs, the skill's large icon (GetSkillIcon(skill, true): img/icons/s_<skill>_overlay.png) in the
     * skill's colour now (GetCurrentSkillColor) at p x 192 px, centred 14 px above the crew's middle, alpha
     * 1 - 2.4p, and the same icon at its own size 16 px left and 30 px above the middle at alpha 2.4p.
     */
    private fun drawLevelUp() {
        val skill = lastLevelUp ?: return
        if (!levelUpRunning || currentAction == Action.DYING || ownerShip?.isPlayerShip != true) return
        val icon = game.getImgIfExists("img/icons/s_${skill.xmlName}_overlay.png") ?: return
        val p = levelUpTime / LEVEL_UP_TIME
        val c = when (getSkillLevel(skill)) {
            SkillLevel.MAX -> Constants.SYS_ENERGY_REPAIR
            SkillLevel.PARTIAL -> Constants.SYS_ENERGY_ACTIVE
            else -> Colour.white
        }
        val x = screenX + 17f
        val y = screenY + 17f
        val fade = (1f - p * 2.4f).coerceIn(0f, 1f)
        if (fade > 0f) icon.draw(x - p * 96f, y - p * 96f - 14f, p * 192f, p * 192f, Colour(c.r, c.g, c.b, c.a * fade))
        val shown = (p * 2.4f).coerceIn(0f, 1f)
        if (shown > 0f) icon.draw(x - 16f, y - 30f, Colour(c.r, c.g, c.b, c.a * shown))
    }

    override fun cloneReadyNow(): Boolean = ownerShip?.clonebay != null

    override fun onStartedDying() {
        super.onStartedDying()
        if (ownerShip?.isPlayerShip == true) game.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.LOST_CREW)
    }

    override fun onFinishedDying() {
        super.onFinishedDying()

        val clonebay = ownerShip?.clonebay ?: return
        // Hyperspace CrewMember::CheckSkills: NO_CLONE races (and crew whose death timer ran out) are never cloned;
        // nor is a crew member killed (Lua Kill) before its first loop set clone_ready
        if (xyz.znix.xftl.hs.HS.active && (!hsCloneReady || xyz.znix.xftl.hs.crew.HsCrewEffects.cloneBlocked(this))) return
        clonebay.addDeadCrew(this)
    }

    override fun onCloned() {
        super.onCloned()

        // Hyperspace CrewMember::Clone (CustomCrew.cpp): a race with cloneLoseSkills false takes its own path, no loss
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.crew.HsCrewEffects.cloneKeepsSkills(this)) return

        // CrewMember::Restart's clone arrival (0x47be80) deducts a tenth of
        // each skill's maximum (rounded down, at least one action).
        for (skill in Skill.values()) {
            val actionsToDeduct = ((skill.actionsPerLevel(blueprint) * 2) * 0.1f).toInt().coerceAtLeast(1)
            val oldLevel = info.skills.getValue(skill)
            val newLevel = (oldLevel - actionsToDeduct * skill.amountPerAction(blueprint)).coerceIn(0f..1f)
            info.skills[skill] = newLevel
        }
    }

    override fun onFinishedRepair(sys: AbstractSystem) {
        super.onFinishedRepair(sys)
        info.browserCounters.increment(ftl.browser.CrewRecordKind.REPAIRS)
        addSkillPoint(Skill.REPAIRS)
    }

    override fun onFinishedExtinguishing() {
        super.onFinishedExtinguishing()

        // Putting out fires doesn't give repair skill. The code for it is
        // there in vanilla, but Repairable::PartialRepair compares the final
        // negative health value to zero to check if it's done, which is
        // thus always false.
    }

    override fun onKilledCrew(enemy: AbstractCrew) {
        super.onKilledCrew(enemy)
        if (enemy is LivingCrew) info.browserCounters.increment(ftl.browser.CrewRecordKind.KILLS)
        addSkillPoint(Skill.COMBAT)
    }

    override fun onSabotagedSystem(system: AbstractSystem) {
        super.onSabotagedSystem(system)

        // Damaging systems isn't any faster with the combat skill, but
        // it still awards experience.
        addSkillPoint(Skill.COMBAT)
    }

    /**
     * A quick way for systems to credit the crewmember with performing a single action.
     */
    fun addSkillPoint(skill: Skill) {
        // CrewMember::IncreaseSkill (0x479b90): only the player's crew learn.
        if (ownerShip?.isPlayerShip != true)
            return

        // The native skill is an integer pair (experience, maximum) where the
        // maximum is twice the actions per colour change; keep the stored
        // fraction on that grid so float drift never leaves a skill one
        // epsilon short of maxed.
        val oldProgress = info.skills.getValue(skill)
        val maxXp = skill.actionsPerLevel(blueprint) * 2
        val newXp = (Math.round(oldProgress * maxXp) + 1).coerceAtMost(maxXp)
        val newProgress = if (newXp >= maxXp) 1f else newXp.toFloat() / maxXp
        info.skills[skill] = newProgress
        info.browserCounters.learned(skill, oldProgress, newProgress)

        // Show the experience indicator on this crewmember's crew box -
        // but only if they actually gained experience. At maximum
        // progress further actions clamp in place, which isn't a gain.
        if (newProgress > oldProgress) {
            lastTrainedSkill = skill
            skillIndicatorTimer = SKILL_INDICATOR_TIME
        }

        // Play the level-up sound when this crewmember reaches a new skill
        // milestone (green at 0.5 progress, yellow at 1.0). Only for the
        // player's crew - you shouldn't hear enemy boarders levelling up.
        val ship = ownerShip
        if (ship != null && ship.isPlayerShip) {
            val crossedMilestone = (oldProgress < 0.5f && newProgress >= 0.5f) ||
                    (oldProgress < 1f && newProgress >= 1f)
            if (crossedMilestone) {
                ship.sys.sounds.getSampleOrWarn("levelup")?.play()
                levelUpTime = 0f
                levelUpRunning = true
                lastLevelUp = skill
            }
        }
    }

    /**
     * True if [skill] has all of its experience (CrewMember::CheckSkills'
     * `first != second` test, 0x479d70).
     */
    fun isSkillMaxed(skill: Skill): Boolean {
        val maxXp = skill.actionsPerLevel(blueprint) * 2
        return Math.round(info.skills.getValue(skill) * maxXp) >= maxXp
    }

    // Helper function
    fun getSkillLevel(skill: Skill): SkillLevel = info.getSkillLevel(skill)

    protected fun getAugmentValue(name: String): Float? {
        return ownerShip?.getAugmentValueOrNull(name) ?: browserStrandedAugments[name]
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        val owner = ownerShip
        val registered = refs.isRegistered(owner)
        SaveUtil.addAttrRef(elem, "ownerShip", refs, if (registered) owner else null)
        if (!registered && owner != null) {
            browserStrandedAugments.clear()
            for (augment in owner.augments) browserStrandedAugments[augment.name] = owner.getAugmentValue(augment.name)
        }
        for ((name, value) in browserStrandedAugments) elem.addContent(Element("browserStrandedAugment").setAttribute("name", name).setAttribute("value", value.toString()))
        info.saveToXML(elem, false)
        if (hsMindControlled) elem.setAttribute("hsMindControlled", "true")
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        super.loadFromXML(elem, refs)

        SaveUtil.getAttrRef(elem, "ownerShip", refs, Ship::class.java) { ownerShip = it }
        browserStrandedAugments.clear()
        for (augment in elem.getChildren("browserStrandedAugment")) browserStrandedAugments[augment.getAttributeValue("name")] = augment.getAttributeValue("value").toFloat()
        info = LivingCrewInfo.loadFromXMLWithRace(elem, blueprint, game)
        hsMindControlled = elem.getAttributeValue("hsMindControlled") == "true"
    }

    companion object {
        val REPAIR_SKILL_BONUS = listOf(0, 10, 20)
        val COMBAT_SKILL_BONUS = listOf(0, 10, 20)

        /**
         * How long (in seconds) the experience indicator stays visible
         * on a crewmember's crew box after they last gained experience.
         */
        const val SKILL_INDICATOR_TIME = 4f

        /** The levelUp AnimationTracker's length (CrewMember constructor; oracle sw4-skill levelUp.time). */
        const val LEVEL_UP_TIME = 2f
    }
}

/**
 * Attributes about a crewmember that isn't spawned into the world.
 *
 * This is used for things like events and shops, to show you a player
 * without spawning them.
 */
class LivingCrewInfo(
    private val game: InGameState,

    race: CrewBlueprint,

    name: String,

    /**
     * The index of this crew's colour selection. This is used to select
     * what colours are applied to the layer filter.
     *
     * This is how different crewmembers can have different colours.
     */
    var colour: Int
) {
    /**
     * The colour index of each layer (CrewBlueprint colorChoices) when layers other than the largest have a choice
     * (Hyperspace races; CrewMember::CycleColorLayer moves every layer), or null while [colour] picks every layer.
     */
    @JvmField var hsLayerColours: IntArray? = null

    /**
     * The player facing name of this crew, eg 'Slocknog' or some
     * other name that's selected by the player, by an event or
     * from the default list of names.
     */
    /** The race; only Hyperspace's race transformation changes it. */
    var race: CrewBlueprint = race
        internal set

    var name: String = name
        set(value) {
            if (field == value)
                return

            field = value
            shortName = findShortName()
        }

    var shortName: String = findShortName()
        private set

    /** Hyperspace CrewMember::SetName: forced, the short name is the whole name, else ShortenName's. */
    fun hsSetName(value: String, force: Boolean) {
        name = value
        shortName = if (force) value else findShortName()
    }

    /**
     * The crew's skills. 0 means no skill, 1 means fully yellow (max level).
     *
     * 0.5 is where the crew's skill turns from white to green, and starts showing
     * a yellow progress bar.
     */
    val skills: EnumMap<Skill, Float> = EnumMap(Skill.entries.associateWith { 0f })
    val browserCounters = ftl.browser.BrowserCrewCounters()

    /**
     * For humans, this determines whether they're male or female.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck") // Intention is clearer like this
    val isFemale: Boolean get() = colour >= race.baseNumberOfColours && race.baseNumberOfColours > 0

    /**
     * The race whose animations and sprite sheet draw this crew: under Hyperspace the race's `<animSheet>` (Outer
     * Expansion's aea_cult_wizard_s04 is drawn with aea_cult_wizard's), as the crew member's own animations are.
     */
    private val sheetRace: String
        get() = if (!xyz.znix.xftl.hs.HS.active) race.name
        else xyz.znix.xftl.hs.crew.CustomCrewManager.definitionOrNull(race.name)?.animSheet?.get(if (isFemale) 0 else 1)
            ?.ifEmpty { null } ?: race.name

    private val portraitImage: Image
        get() = (if (xyz.znix.xftl.hs.HS.active) (game.animations.getOrNull("${race.name}_portrait")
            ?: game.animations.getOrNull("${sheetRace}_portrait"))?.spriteAt(game, 0)
            ?: game.animations.sheet(sheetRace)?.let { xyz.znix.xftl.AnimationSpec(it, "${race.name} sheet portrait", 0, 0, 1, 1f).spriteAt(game, 0) }
            ?: game.animations.crewStrip("${race.name}_portrait").spriteAt(game, 0)
        else game.animations.crewStrip("${race.name}_portrait").spriteAt(game, 0))

    /**
     * Draw this crew's portrait, without having to construct the crewmember instance.
     *
     * Useful for events and shops.
     */
    fun drawPortrait(x: Int, y: Int, scale: Float = 1f) {
        drawImage(
            x.f, y.f,
            x.f + portraitImage.width * scale, y.f + portraitImage.height * scale,
            portraitImage,
            1f,
            false
        )
    }

    // This is moved out of LivingCrew so it can be used in drawPortrait.
    fun drawImage(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        baseFrame: Image,
        alpha: Float,
        skipColours: Boolean
    ) {
        val texX0 = baseFrame.textureOffsetX
        val texY0 = baseFrame.textureOffsetY
        val texX1 = texX0 + baseFrame.width
        val texY1 = texY0 + baseFrame.height

        // Find the sprite sheet for this race. In the case of humans, there's
        // separate male/female sprite sheets, with the unfortunate naming
        // of 'human' for the male version and 'female' for the female version.
        val effectiveColour = if (isFemale) colour - race.baseNumberOfColours else colour
        val baseAnimationName = if (isFemale) "female" else race.name
        val imageNames = if (isFemale) race.femaleLayerImageNames else race.layerImageNames

        val baseImagePath = when {
            isFemale -> "img/people/female_base.png"
            // Hyperspace races without a portrait animation: their sheet (see AbstractCrew.buildPortraitAnim).
            xyz.znix.xftl.hs.HS.active && game.animations.getOrNull("${baseAnimationName}_portrait") == null ->
                game.animations.getOrNull("${sheetRace}_portrait")?.sheet?.sheetPath ?: game.animations.sheet(sheetRace)?.sheetPath
                    ?: return
            else -> game.animations.getOrNull("${baseAnimationName}_portrait")?.sheet?.sheetPath ?: return
        }
        val fullBaseImage = game.getImg(baseImagePath)

        // Draw the base image, without any tinting
        fullBaseImage.draw(x0, y0, x1, y1, texX0.f, texY0.f, texX1.f, texY1.f, alpha, Colour.white)

        if (skipColours)
            return

        // Draw all the layers
        for ((index, path) in imageNames.withIndex()) {
            val layer = game.getImg(path)

            val colours = race.colourFilters[index]
            val chosen = hsLayerColours?.takeIf { it.size == race.colourFilters.size }?.get(index)
            val filter = colours[(chosen ?: effectiveColour) % colours.size]

            layer.draw(x0, y0, x1, y1, texX0.f, texY0.f, texX1.f, texY1.f, alpha, filter)
        }
    }

    fun getSkillLevel(skill: Skill): SkillLevel {
        val progress = skills.getValue(skill)
        return when {
            progress == 1f -> SkillLevel.MAX
            progress >= 0.5f -> SkillLevel.PARTIAL
            else -> SkillLevel.BASE
        }
    }

    fun drawSkillProgressBar(g: Graphics, x: Int, y: Int, width: Int, height: Int, skill: Skill) {
        val level = getSkillLevel(skill)

        val baseColour = when (level) {
            SkillLevel.MAX -> Constants.SYS_ENERGY_REPAIR
            SkillLevel.PARTIAL -> Constants.SYS_ENERGY_ACTIVE
            else -> Colour.transparent
        }
        val barColour = when (level) {
            // Max doesn't draw a bar
            SkillLevel.PARTIAL -> Constants.SYS_ENERGY_REPAIR
            else -> Constants.SYS_ENERGY_ACTIVE
        }

        // Convert the 0-1 progress amount (where the green level is 0.5)
        // to 0-1 over the range of a single colour.
        val rawSkillProgress = skills.getValue(skill)
        val progress = when (level) {
            SkillLevel.MAX -> 0f
            SkillLevel.PARTIAL -> (rawSkillProgress - 0.5f) * 2f
            else -> rawSkillProgress * 2f
        }

        // Draw the outer box around the bar.
        // Note that drawRect draws its lower and right lines outside
        // the specified region, so -1 from width and height.
        g.colour = Colour.white
        g.drawRect(x.f, y.f, width - 1f, height - 1f)

        val innerBarWidth = width - 2
        val progressWidth = (innerBarWidth * progress).roundToInt()

        g.colour = baseColour
        g.fillRect(x + 1f, y + 1f, innerBarWidth.f, height - 2f)

        g.colour = barColour
        g.fillRect(x + 1f, y + 1f, progressWidth.f, height - 2f)

        // Draw the white divider line between yellow and green sections
        if (level == SkillLevel.PARTIAL) {
            g.colour = Colour.white
            g.fillRect(x + 1f + progressWidth, y + 1f, 1f, height - 2f)
        }
    }

    private fun findShortName(): String {
        // If we're running in an automated test, we can't use fonts.
        if (game.isRunningAutomatedTest) {
            return name
        }

        // ShortenName measures with freetype::measurePrintLines (0x595dc0, font 6): the glyph extents, not the
        // trailing advance, so "Matthieu" (49px) stays whole. A name of bytes up to 0xD1 takes font 6 with
        // ignoreLanguage, the English glyphs in Japanese and Chinese too.
        val font = game.getFont(PlayerShipUI.CREW_NAME_FONT).let {
            if (xyz.znix.xftl.rendering.NativeFonts.nameIgnoresLanguage(name)) xyz.znix.xftl.rendering.NativeFonts.english(it) else it
        }
        return game.nameManager.shorten(name) { font.getExtentWidth(it) }
    }

    fun saveToXML(elem: Element, includeRace: Boolean = true) {
        if (includeRace) {
            SaveUtil.addAttr(elem, "race", race.name)
        }
        SaveUtil.addAttr(elem, "selectedName", name)
        SaveUtil.addAttrInt(elem, "colour", colour)
        hsLayerColours?.let { elem.setAttribute("hsLayerColours", it.joinToString(",")) }
        elem.addContent(browserCounters.save())

        for ((skill, value) in skills) {
            if (value == 0f)
                continue

            val skillElem = Element("skill")
            SaveUtil.addAttr(skillElem, "id", skill.name)
            SaveUtil.addAttrFloat(skillElem, "value", value)
            elem.addContent(skillElem)
        }
    }

    companion object {
        /**
         * A random crew member as the original generates one: the colour
         * of each layer, then a name from the name table (see
         * [NativeCrewFactory]).
         */
        @JvmStatic
        fun generateRandom(race: CrewBlueprint, game: InGameState): LivingCrewInfo =
            game.crewFactory.createRandom(game, race)

        // CrewBlueprint's random skills (0x6058a0): one-level boosts by world
        // level, as (min, max) pairs for levels 0-7.
        private val RANDOM_SKILL_LEVELS = intArrayOf(0, 0, 0, 0, 0, 1, 0, 2, 1, 2, 1, 2, 1, 3, 2, 3)

        /**
         * Give store crew and unskilled event crew their random starting
         * skills (CrewBlueprint 0x6058a0): min + rand%(max-min+1) one-level
         * boosts (at most four) by world level, each to one of two distinct
         * random skills, capped at the maximum. The step count is drawn
         * even past level 7 (where it is always zero).
         */
        fun applyRandomSkills(info: LivingCrewInfo, worldLevel: Int, rand: Random) {
            val level = worldLevel.coerceAtLeast(0)
            var min = 0
            var max = 0
            if (level <= 7) {
                min = RANDOM_SKILL_LEVELS[level * 2]
                max = RANDOM_SKILL_LEVELS[level * 2 + 1]
            }
            val steps = min + rand.nextInt(max - min + 1)
            val boosts = kotlin.math.min(steps, 4)

            val skills = Skill.values()
            val first = skills[rand.nextInt(6)]
            var second = first
            while (second == first)
                second = skills[rand.nextInt(6)]

            if (steps > 0) {
                repeat(boosts) {
                    val skill = if (rand.nextInt(2) == 0) first else second
                    info.skills[skill] = kotlin.math.min(info.skills.getValue(skill) + 0.5f, 1f)
                }
            }
        }

        /** A crew member with a given name (event crew such as Slocknog): the colours are still drawn. */
        fun generateWithName(race: CrewBlueprint, game: InGameState, name: String): LivingCrewInfo {
            val factory = game.crewFactory
            val colours = factory.drawColours(race)
            return factory.layered(LivingCrewInfo(game, race, name, factory.colourIndex(race, colours, true)), colours)
        }

        fun loadFromXML(elem: Element, game: InGameState): LivingCrewInfo {
            val raceName = SaveUtil.getAttr(elem, "race")
            val race = game.blueprintManager[raceName] as CrewBlueprint
            return loadFromXMLWithRace(elem, race, game)
        }

        fun loadFromXMLWithRace(elem: Element, race: CrewBlueprint, game: InGameState): LivingCrewInfo {
            val name = SaveUtil.getAttr(elem, "selectedName")
            val colour = SaveUtil.getAttrInt(elem, "colour")
            val info = LivingCrewInfo(game, race, name, colour)
            elem.getAttributeValue("hsLayerColours")?.let { v ->
                info.hsLayerColours = v.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
            }
            info.browserCounters.load(elem.getChild("browserCrewCounters"))

            // Skills are omitted if they're set to zero, which is what the default is anyway.
            for (skillElem in elem.getChildren("skill")) {
                val skillName = SaveUtil.getAttr(skillElem, "id")
                val value = SaveUtil.getAttrFloat(skillElem, "value")

                val skill = Skill.valueOf(skillName)
                info.skills[skill] = value
            }

            return info
        }
    }
}

enum class Skill(
    val xmlName: String,

    /**
     * The number of actions a non-human (no 10% boost) has to perform
     * to increase their skill level from 0->50% or 50->100% (a single
     * colour change).
     */
    val actionsPerLevel: Int
) {
    PILOTING("pilot", 15),
    ENGINES("engines", 15),
    SHIELDS("shields", 55),
    WEAPONS("weapons", 65),
    REPAIRS("repair", 18),
    COMBAT("combat", 8),
    ;

    val iconPath: String = "img/people/skill_${xmlName}_white.png"

    // Divide by 2f since the skill level from 0-1 represents the range
    // of no skills to yellow (with green being 0.5), while actionsPerLevel
    // is the number of actions to make one colour change (or 0.5 progress).
    val amountPerAction = 1f / (actionsPerLevel * 2f)

    /**
     * The actions per colour change for [race]. CrewMember's constructor
     * (0x485df0) gives humans smaller skill maxima (26, 26, 100, 116, 32, 14)
     * than the table every other race keeps (0x8847a0, 30, 30, 110, 130, 36, 16).
     */
    fun actionsPerLevel(race: CrewBlueprint): Int {
        // Hyperspace (CrewMember_Extend::Initialize, CrewBlueprint::RandomSkills) sets each defined race's maximum
        // to twice its <skills> requirement, the vanilla table's values by default.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.CustomCrewManager.definitionOrNull(race.name)?.let {
            return it.skillsDef.skills.getValue(xyz.znix.xftl.hs.crew.SkillsDefinition.ORDER[ordinal]).requirement
        }
        return if (race.name == "human") HUMAN_MAXIMA[ordinal] / 2 else actionsPerLevel
    }

    fun amountPerAction(race: CrewBlueprint): Float = 1f / (actionsPerLevel(race) * 2f)

    private companion object {
        val HUMAN_MAXIMA = intArrayOf(26, 26, 100, 116, 32, 14)
    }

    fun getDescription(game: InGameState, level: SkillLevel): String {
        val levelNum = level.ordinal

        return when (this) {
            PILOTING -> game.translator["pilot_skill"].replaceArg(Piloting.SKILL_BONUSES[levelNum])
            ENGINES -> game.translator["engine_skill"].replaceArg(Engines.SKILL_BONUSES[levelNum])
            SHIELDS -> game.translator["shield_skill"].replaceArg(Shields.SKILL_BONUSES[levelNum])
            WEAPONS -> game.translator["weapon_skill"].replaceArg(Weapons.SKILL_BONUSES[levelNum])

            REPAIRS -> when (level) {
                SkillLevel.BASE -> game.translator["repair_0"]
                else -> game.translator["repair_skilled"].replaceArg(LivingCrew.REPAIR_SKILL_BONUS[levelNum])
            }

            COMBAT -> when (level) {
                SkillLevel.BASE -> game.translator["combat_0"]
                else -> game.translator["combat_skilled"].replaceArg(LivingCrew.COMBAT_SKILL_BONUS[levelNum])
            }
        }
    }
}

enum class SkillLevel {
    BASE, // No advantage
    PARTIAL, // Medium advantage
    MAX; // Skill maxed
}
