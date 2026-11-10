package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.*
import kotlin.math.max

// TODO vertically offset all the non-system boxes correctly

class InfoPanel private constructor(private val game: InGameState?, private val hangar: xyz.znix.xftl.hangar.SelectShipState?) {
    constructor(game: InGameState): this(game,null)
    constructor(hangar: xyz.znix.xftl.hangar.SelectShipState): this(null,hangar)
    private val translator get() = game?.translator ?: hangar!!.translator
    private val windowRenderer get() = game?.windowRenderer ?: hangar!!.windowRenderer
    private fun getImg(path: String): Image = game?.getImg(path) ?: hangar!!.getImg(path)
    private fun getFont(name: String,scale: Float=1f): SILFontLoader = game?.getFont(name,scale) ?: hangar!!.getFont(name).apply { this.scale=scale }

    private val systemLevelFont = getFont("JustinFont10")
    private val numberFont = getFont("num_font")
    private val titleFont = getFont("c&cnew", 2f)
    private val descriptionFont = getFont("JustinFont10")
    private val tipFont = getFont("JustinFont8")

    /**
     * The touch layout's purchase box goes below whatever this panel drew: the lowest edge since the owner last set this
     * to 0 (the owner does so each frame).
     */
    @JvmField
    var touchBottom = 0

    var position: IPoint = ConstPoint.ZERO
    /** InfoBox::SetDescription's width argument; null keeps the default. */
    var customWidth: Int? = null
    private val panelWidth get() = customWidth ?: if(hangar!=null) 323 else 333
    /** InfoBox::SetDescription wraps at the frame width less 18. */
    private val textWidth get() = panelWidth - 18

    fun drawAugment(blueprint: AugmentBlueprint) {
        // Hyperspace InfoBox::SetBlueprint(augment) (RedesignedTooltips.cpp): the description (plus the scrap value
        // with redesignedAugmentTooltips) in a box of its height: measured "\n" + text in font 10 at 305, + 58.
        if (xyz.znix.xftl.hs.HS.active) {
            val text = xyz.znix.xftl.hs.render.HsTooltips.augmentDescription(blueprint, translator)
            // HS also sets InfoBox::warning (augment_no_effect and the augments this one makes useless) and measures
            // the box over warning + "\n" + text.
            val blueprints = game?.blueprintManager ?: hangar!!.blueprints
            val warning = xyz.znix.xftl.hs.render.HsTooltips.augmentWarning(blueprint, translator, blueprints)
            val h = hsLines(descriptionFont, 305, warning + "\n" + text) * 17 + 58
            drawDescriptionBox(blueprint.title, GameText.literal(text), null, emptyList(), h, warning)
            return
        }
        drawDescriptionBox(blueprint.title, blueprint.desc, null, emptyList(), INFO_HEIGHT_AUGMENT)
    }

    /** freetype::easy_measurePrintLines' line count (the height is that times the line height). */
    private fun hsLines(font: SILFontLoader, width: Int, text: String): Int = font.wrapString(text.trimEnd('\n'), width).size

    /** Hyperspace's InfoBox::SetText(title, text, -1, -1, EXPAND_DOWN): the frame is as tall as its text needs. */
    fun drawAutoBox(title: String, text: String) {
        val h = hsLines(titleFont, textWidth, title) * 20 + hsLines(descriptionFont, textWidth, text) * 17 + 24
        drawDescriptionBox(GameText.literal(title), GameText.literal(text), null, emptyList(), h)
    }

    fun drawItem(blueprint: ItemBlueprint) {
        drawDescriptionBox(blueprint.title, blueprint.desc, null, emptyList(), INFO_HEIGHT_ITEM)
    }

    fun drawDrone(blueprint: DroneBlueprint) {
        // Hyperspace InfoBox::SetBlueprint(drone) with redesignedDroneTooltips: the stats in the description, the
        // box max(title + text + 45, 162) high (title in font 16, text in font 10).
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsTooltips.droneDescription(blueprint, translator)?.let { text ->
            val title = blueprint.title?.get(translator) ?: ""
            val h = max(hsLines(titleFont, textWidth, title) * 20 + hsLines(descriptionFont, textWidth, text) * 17 + 45, INFO_HEIGHT_DRONE)
            drawDescriptionBox(blueprint.title, GameText.literal(text), if(hangar==null) blueprint.tip else null, emptyList(), h)
            return
        }
        val lines = ArrayList<String>()
        lines += translator["required_power"].replaceArg(blueprint.power)
        lines += translator["drone_required"]
        drawDescriptionBox(blueprint.title, blueprint.desc, if(hangar==null) blueprint.tip else null, lines, INFO_HEIGHT_DRONE)
    }

    fun drawWeapon(blueprint: AbstractWeaponBlueprint) {
        // Hyperspace WeaponBlueprint::GetDescription (RedesignedTooltips.cpp): <descriptionOverride> (or, with
        // redesigned weapon tooltips, <advancedDescriptionOverride>) replaces the whole description.
        // Under Hyperspace the whole text is HS's (HsTooltips.weaponDescription: overrides, redesigned or HS's list).
        val hsOverride = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsTooltips.weaponDescription(blueprint, translator) else null
        if (hsOverride != null) {
            // The box grows with a long text, so the last lines don't run under the tip.
            val title = blueprint.title?.get(translator) ?: ""
            val h = max(hsLines(titleFont, textWidth, title) * 20 + hsLines(descriptionFont, textWidth, hsOverride) * 17 + 45, INFO_HEIGHT_WEAPON)
            drawDescriptionBox(blueprint.title, xyz.znix.xftl.GameText.literal(hsOverride), if(hangar==null) blueprint.tip else null, emptyList(), h)
            return
        }
        val lines = ArrayList<String>()

        // Build all the weapon attributes that appear when hovering over it

        lines += translator["required_power"].replaceArg(blueprint.power)
        lines += translator["charge_time"].replaceArg(UIUtils.formatFloat(blueprint.chargeTime))
        if (blueprint.boost?.type == AbstractWeaponBlueprint.BoostType.COOLDOWN) {
            val maxBoost = blueprint.boost!!.maxCount * blueprint.boost!!.perShot
            lines += translator["boost_power_speed"]
            lines += translator["speed_cap"].replaceArg(maxBoost)
        }
        if (blueprint.boost?.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
            // We have to include the initial damage in the max value shown.
            // How does vanilla FTL do this?
            val baseDamage = max(blueprint.damage, blueprint.ionDamage)
            val maxDamage = blueprint.boost!!.maxCount * blueprint.boost!!.perShot + baseDamage
            lines += translator["boost_power_damage"]
            lines += translator["damage_cap"].replaceArg(maxDamage)
        }
        if (blueprint.missilesUsed > 0) {
            lines += translator["requires_missiles"]
        }
        if (blueprint is FlakBlueprint) {
            lines += translator["shots"].replaceArg(blueprint.projectileCount)
        } else if (blueprint.shots != 1 || blueprint is LaserBlueprint) {
            lines += translator["shots"].replaceArg(blueprint.shots)
        }
        if (blueprint.chargeLevels != null) {
            lines += translator["charge"].replaceArg(blueprint.chargeLevels!!)
        }
        if (blueprint is BeamBlueprint) {
            lines += translator["damage_room"].replaceArg(blueprint.damage)
        } else {
            lines += translator["damage_shot"].replaceArg(blueprint.damage)
        }
        if (blueprint.shieldPiercing != 0) {
            lines += translator["shield_piercing"].replaceArg(blueprint.shieldPiercing)
        }
        addChanceString(lines, "fire_chance", blueprint.fireChance)
        addChanceString(lines, "breach_chance", blueprint.breachChance)
        if(hangar==null || hangar.advancedEdition) addChanceString(lines, "stun_chance", blueprint.stunChance)
        // WeaponBlueprint::GetDescription (1.6.14 0x605a80): ion damage, stun, then the blueprint's own personnel damage
        // (only above 0, in hp) and system damage, not the hull damage they add in a hit (1.6.14 ps-w3-van s-h-weap1:
        // Hull Laser I has no crew damage line).
        if (blueprint.ionDamage != 0) {
            lines += translator["ion_damage"].replaceArg(blueprint.ionDamage)
        }
        if (blueprint.stun != 0) {
            lines += translator["stun_damage"].replaceArg(blueprint.stun)
        }
        if (blueprint.personnelDamage >= 1) {
            lines += translator["personnel_damage"].replaceArg(blueprint.personnelDamage * 15)
        }
        if (blueprint.sysDamage != 0) {
            lines += translator["system_damage"].replaceArg(blueprint.sysDamage)
        }
        if (blueprint.hullBust != 0) {
            lines += translator["double_damage"]
        }

        drawDescriptionBox(blueprint.title, blueprint.desc, if(hangar==null) blueprint.tip else null, lines, INFO_HEIGHT_WEAPON)
    }

    fun drawCrew(g: Graphics, info: LivingCrewInfo, skills: Boolean = true, nativeLift: Boolean = true) {
        // InfoBox::OnRender (1.6.9 0x54c710): a crew member's box with the skills (CrewStoreBox::SetInfoBox 0x518d20, the
        // crew screen) is drawn 40 above the InfoBox's place, 323 wide like the item boxes (1.6.14 ps-w3-van s-h-crew1,
        // u-h-crew1: 942..1265 from y 119). Desktop layout only; the hangar has its own box.
        if (skills && hangar == null && !TouchMode.enabled && !xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.isTouchUi) {
            val (place, width) = position to customWidth
            position = ConstPoint(place.x, place.y - if (nativeLift) 40 else 0)
            customWidth = 323
            try { drawCrewBoxes(g, info, skills) } finally { position = place; customWidth = width }
            return
        }
        drawCrewBoxes(g, info, skills)
    }

    private fun drawCrewBoxes(g: Graphics, info: LivingCrewInfo, skills: Boolean) {
        val raceName = translator[info.race.title!!]
        val title = GameText.literal("${info.name} ($raceName)")
        val powers = info.race.powerStringIds.map { "-" + translator[it] }
        // Hyperspace InfoBox::SetBlueprint(crew): the box is max(title + description + 28, 183) high
        var height = INFO_HEIGHT_CREW
        if (xyz.znix.xftl.hs.HS.active) {
            val desc = (info.race.desc?.get(translator) ?: "") + if (powers.isEmpty()) "" else "\n\n" + powers.joinToString("\n")
            height = max(hsLines(titleFont, textWidth, title.get(translator)) * 20 + hsLines(descriptionFont, textWidth, desc) * 17 + 28, 183)
        }
        drawDescriptionBox(title, info.race.desc, null, powers, height)

        if (!skills) return

        // Draw the skills box
        val skillsY = position.y + height + 24
        windowRenderer.render(position.x, skillsY, 323, 242)
        touchBottom = max(touchBottom, skillsY + 242)

        val leftIconX = position.x + 9
        val rightIconX = position.x + 164

        titleFont.drawString(position.x + 28f, skillsY + 32f, translator["crew_skills"], Colour.white)

        for ((index, skill) in Skill.values().withIndex()) {
            val row = index / 2
            val iconX = if (index % 2 == 0) leftIconX else rightIconX
            val iconY = skillsY + 46 + row * 66

            val iconColour = when (info.getSkillLevel(skill)) {
                SkillLevel.MAX -> Constants.SYS_ENERGY_REPAIR
                SkillLevel.PARTIAL -> Constants.SYS_ENERGY_ACTIVE
                else -> Constants.UI_BACKGROUND_GLOW_COLOUR
            }
            val icon = getImg(skill.iconPath)
            icon.draw(iconX, iconY, iconColour)

            // Draw the skill bar
            info.drawSkillProgressBar(g, iconX + 30, iconY + 8, 110, 9, skill)

            // Draw the description text
            val level = info.getSkillLevel(skill)
            val baseDescription = skill.getDescription(checkNotNull(game), level)
            val skillLocName = when (skill) {
                Skill.PILOTING -> "pilot"
                Skill.ENGINES -> "engines"
                Skill.SHIELDS -> "shields"
                Skill.WEAPONS -> "weapons"
                Skill.REPAIRS -> "repair"
                Skill.COMBAT -> "combat"
            }
            val description = translator[skillLocName + "_skill_infobox"].replaceArg(baseDescription)
            val lines = tipFont.wrapString(description, 140)
            val textCentreX = iconX + 85

            for ((i, line) in lines.withIndex()) {
                val lineY = iconY + 32 + i * 15
                tipFont.drawStringCentred(textCentreX.f, lineY.f, 0f, line, Colour.white)
            }
        }
    }

    /**
     * Note: this does not render the system power bars!
     */
    fun drawDescriptionBoxSystem(blueprint: SystemBlueprint) {
        drawDescriptionBox(blueprint.title, blueprint.desc, null, emptyList(), INFO_HEIGHT_SYSTEM)
    }

    /**
     * Note: this does not render the system power bars!
     */
    fun drawDescriptionBoxSystem(system: AbstractSystem) {
        // The artillery system uses it's weapon's title/description.
        drawDescriptionBox(system.title, system.description, null, emptyList(), INFO_HEIGHT_SYSTEM)
    }

    fun drawDescriptionBox(
        title: GameText?,
        description: GameText?,
        tip: GameText?,
        extraLines: List<String>,
        height: Int,
        warning: String = ""
    ) {
        windowRenderer.render(position.x, position.y, panelWidth, height)
        touchBottom = max(touchBottom, position.y + height)

        var y = position.y + 30

        val titleStr = title?.get(translator)
        if (titleStr != null) {
            val lines = titleFont.wrapString(titleStr, textWidth)
            for (line in lines) {
                titleFont.drawString(
                    position.x + 11f, y.f,
                    line,
                    Colour.white
                )
                y += 20
            }
        }

        // InfoBox::OnRender: the description starts 15px below the title block.
        y += 3

        // InfoBox::OnRender (0x564410): a warning (InfoBox::warning; only Hyperspace's augment boxes set one here)
        // starts 10 px below the title in (255, 120, 120), and the description follows straight under it.
        if (warning.isNotEmpty()) {
            y -= 5
            for (line in descriptionFont.wrapString(warning, textWidth)) {
                descriptionFont.drawString(position.x + 11f, y.f, line, WARNING_COLOUR)
                y += 17
            }
        }

        val descriptionStr = description?.get(translator)
        if (descriptionStr != null) {
            val lines = descriptionFont.wrapString(descriptionStr, textWidth)

            for (line in lines) {
                descriptionFont.drawString(position.x + 11f, y.f, line, Colour.white)
                y += 17
            }

            // Draw the extra lines, which are for stuff like weapon attributes.
            y += 17
            for (extra in extraLines) {
                for (line in descriptionFont.wrapString(extra, textWidth)) {
                    descriptionFont.drawString(position.x + 11f, y.f, line, Colour.white)
                    y += 17
                }
            }
        }

        if (tip != null) {
            val tipStr = translator[tip]
            // InfoBox::CreateTipBox (1.6.14 0x55d980): the tip wraps in font 9 at the description's width + 2 and its frame
            // is 323 wide (1.6.14 ps-w3-van s-h-weap1: "...a system or a subsystem." on two lines).
            // Desktop layout only; the touch layout keeps its own.
            val desktop = !TouchMode.enabled && !xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.isTouchUi
            val tipLines = tipFont.wrapString(tipStr, if (desktop) textWidth + 2 else panelWidth - 30)

            val tipY = position.y + height + 17
            val tipHeight = 31 + tipLines.size * 15

            windowRenderer.render(position.x, tipY, if (desktop) 323 else panelWidth, tipHeight)
            touchBottom = max(touchBottom, tipY + tipHeight)

            var lineY = tipY + 27
            for (line in tipLines) {
                tipFont.drawString(position.x + 13f, lineY.f, line, Colour.white)
                lineY += 15
            }
        }
    }

    /**
     * The levels box of a system. [maxPower]: InfoBox::maxPower, the ship system's maximum (SetSystem) or the blueprint's
     * (a system for sale). Hyperspace (CustomUpgrades.cpp InfoBox::OnRender / RenderPowerBoxesPlain / GetLevelDescription
     * hooks): over 8 levels the box shows eight of them, scrolled so the reached level sits four below the top (or the
     * top eight), and writes the prices itself, where the original puts them (oracle sw4-up24 u-h0: HS's location + 105
     * lands on this 98).
     */
    fun drawSystemPowerBox(g: Graphics, system: SystemBlueprint, energyLevels: Int, undoablePower: Int, maxPower: Int = system.maxPower) {
        val x = position.x
        val y = position.y + 133
        val totalWidth = panelWidth
        touchBottom = max(touchBottom, y + 226)

        val scrapIcon = getImg("img/upgradeUI/details_scrap.png")

        val maxNonUndoable = energyLevels - undoablePower

        // InfoBox::OnRender (1.6.9 0x54c710): details_base_A at the box's x, then details_base_B whole, (width - A - C) / B
        // times, then details_base_C after the last B; the level rows are A - 75 + 10 + B x that count wide (rather
        // than B stretched up to a C at the right edge: the ship window has room for two Bs)
        val leftSideImg = getImg("img/upgradeUI/details_base_A.png")
        val middleImg = getImg("img/upgradeUI/details_base_B.png")
        val rightSideImg = getImg("img/upgradeUI/details_base_C.png")
        val (middleCount, rowWidth) = detailsLayout(totalWidth, leftSideImg.width, middleImg.width, rightSideImg.width)

        val scroll = xyz.znix.xftl.hs.HS.active && maxPower > 8
        var increment = 0
        if (scroll) {
            increment = energyLevels - 4
            if (maxPower - energyLevels < 4) increment -= 4 - (maxPower - energyLevels)
            if (increment < 0) increment = 0
        }

        // Draw the level text
        for (row in 0 until 8) {
            val i = row + increment
            val canHaveLevel = i < maxPower
            val hasLevel = i < energyLevels
            val undoable = hasLevel && i >= maxNonUndoable
            val boxY = y + 192 - row * 26

            // The details images are drawn on top, so we have to paint
            // the background that the text sits on here.
            g.colour = when (canHaveLevel) {
                // InfoBox::detailsBarOn / detailsBarOff (Hyperspace <colors><systemDetails>)
                true -> xyz.znix.xftl.hs.render.HsColors.detailsBarOn()
                false -> xyz.znix.xftl.hs.render.HsColors.detailsBarOff()
            }
            // Ends inside the right frame: 261px in the 333px ship window, 251px in the hangar.
            g.fillRect(x + 68f, boxY.f, rowWidth.f, 24f)

            if (!canHaveLevel) {
                continue
            }

            // InfoBox shows the scrap icon on every level the system can reach,
            // and prices only for levels the player can buy or refund.
            scrapIcon.draw(x + 68, boxY)
            if (!hasLevel || undoable) {
                // a level past the blueprint's price list (HS: a ship `max` above it) reads past InfoBox's vector in the
                // original; as the upgrade box, it cannot be bought (oracle sw4-up24 u-h0: "--" under MV's threshold)
                val price = if (xyz.znix.xftl.hs.HS.active && i - 1 >= system.upgradeCost.size) Int.MAX_VALUE else system.upgradeCost.getOrElse(i - 1) { -1 }
                numberFont.drawString(x + 98f, boxY + 19f, xyz.znix.xftl.hs.render.HsUpgrades.priceText(price), Colour.white)
            }

            val description = xyz.znix.xftl.hs.lua.LuaShipEvents.levelName(system, i, false, translator)
            systemLevelFont.drawString(x + 142f, boxY + 17f, description, Colour.white)
        }

        val offsetY = y + 6 // Due to the ledge at the top of the left side

        // The images are drawn on top of the text
        leftSideImg.draw(x - ShipWindow.GLOW_WIDTH, y - ShipWindow.GLOW_WIDTH)
        var partX = x - ShipWindow.GLOW_WIDTH + leftSideImg.width
        repeat(middleCount) {
            middleImg.draw(partX, offsetY - ShipWindow.GLOW_WIDTH)
            partX += middleImg.width
        }
        rightSideImg.draw(partX, offsetY - ShipWindow.GLOW_WIDTH)

        // Finally, draw on the energy bars
        for (i in increment until minOf(maxPower, increment + 8)) {
            val hasLevel = i < energyLevels
            val undoable = hasLevel && i >= maxNonUndoable

            g.colour = when {
                undoable -> Constants.SYS_ENERGY_PURCHASE_UNDOABLE
                hasLevel -> Constants.SYS_ENERGY_ACTIVE
                else -> Constants.UPGRADE_DETAILS_POWER_OFF
            }
            g.fillRect(x + 20f, y.f + 195 - (i - increment) * 26, 28f, 18f)
        }
    }

    private fun addChanceString(lines: ArrayList<String>, baseKey: String, chance: Int) {
        // Always look this up to quickly catch invalid keys
        val baseStr = translator[baseKey]

        if (chance == 0) {
            return
        }

        val chanceKey = when (chance) {
            in 1..3 -> "chance_low"
            in 4..6 -> "chance_medium"
            else -> "chance_high"
        }

        val chanceStr = translator[chanceKey]
        lines += baseStr.replaceArg(chanceStr)
    }

    companion object {
        /**
         * InfoBox::OnRender's level box for a panel [panelWidth] wide (the box is that plus the glow on both sides): how
         * many whole details_base_B go between A and C, and how wide the level rows are (A - 75 + 10 + B x count).
         */
        @JvmStatic
        fun detailsLayout(panelWidth: Int, left: Int, middle: Int, right: Int): Pair<Int, Int> {
            val count = (panelWidth + 2 * ShipWindow.GLOW_WIDTH - left - right) / maxOf(1, middle)
            return count to left - 75 + 10 + middle * count
        }

        /** InfoBox::OnRender's warning colour (0x87e390: 1, 120/255, 120/255, 1). */
        private val WARNING_COLOUR = Colour(255, 120, 120)
        const val INFO_HEIGHT_SYSTEM = 121
        const val INFO_HEIGHT_AUGMENT = 136
        const val INFO_HEIGHT_CREW = 168
        const val INFO_HEIGHT_ITEM = 121
        const val INFO_HEIGHT_DRONE = 162
        const val INFO_HEIGHT_WEAPON = 252
    }
}
