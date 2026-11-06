package xyz.znix.xftl.hs.render

import xyz.znix.xftl.Constants
import xyz.znix.xftl.GameText
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.hangar.HangarLayout
import xyz.znix.xftl.hangar.SelectShipState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsAchievements
import xyz.znix.xftl.hs.ship.HsHangar
import xyz.znix.xftl.hs.ship.HsShipUnlocks
import xyz.znix.xftl.hs.ship.HsShips
import xyz.znix.xftl.hs.ship.HsVictories
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomShipSelect.cpp (ShipBuilder::OnRender,
// MouseMove, MouseClick hooks, CreateEquipmentBoxes, MenuScreen constructor, ShowAchievementsForShip, TextLibrary::GetText
// "max_augments") and src/game/Gameplay/ShipUnlocks.cpp (CAchievement::OnRender and AchievementTracker::SetTooltip hooks
// for custom achievements). This engine is AGPL-3.0-or-later.

/**
 * The Hyperspace additions to the hangar's ship builder screen: the ship-achievement strip (own achievements, or one
 * icon per victory ending), the victory/quest icons of custom ships, the reactor box, missile/drone/crew-limit counts,
 * augment pages beyond three slots and dummy equipment slots. One per hangar state; vanilla never constructs it.
 */
class HsHangarUi(private val state: SelectShipState, private val font: (Int) -> NativeFonts.NativeFont) {
    companion object {
        /** `CustomShipSelect::showShipAchievements`, toggled by the trophy icon; null = the hyperspace.xml value. */
        @JvmStatic var showShipAchievementsToggle: Boolean? = null

        /** Where the shared InfoBox goes in the hangar. */
        const val REACTOR_X = 335
        const val REACTOR_Y = 380

        @JvmStatic
        fun globalShowShipAchievements(): Boolean = showShipAchievementsToggle ?: HS.cfg().showShipAchievements

        /** `CustomShipSelect::GetInstance()->GetDefinition(name).augSlots` with the ship's AUGMENT_SLOT augments. */
        @JvmStatic
        fun augSlots(shipName: String, augments: List<String>, augmentValue: (String) -> Float): Int {
            var n = HsShips.def(shipName).augSlots
            for (a in augments) if (a == "AUGMENT_SLOT") n += augmentValue(a).toInt()
            return n
        }
    }

    private val cfg get() = HS.cfg()
    private val bp get() = state.blueprint
    private val mouse get() = state.mousePos

    /** The touch layout's panels: these extras keep to the right edge of each. */
    private val pagerDx get() = HangarLayout.pick(0, -251)
    private val pagerDy get() = HangarLayout.pick(0, 46)
    private val weaponsDx get() = HangarLayout.pick(0, -221)
    private val crewDx get() = HangarLayout.pick(0, -135)
    private val reactorY get() = REACTOR_Y + HangarLayout.systemDy

    /** HS's per-ship page of augments (g_currentAugPage), reset when another ship is shown. */
    var augPage = 0
        private set
    private var lastShip = ""

    private fun checkShip() {
        if (lastShip != state.selectedShipId) { lastShip = state.selectedShipId; augPage = 0 }
    }

    val augSlots: Int
        get() = augSlots(state.selectedShipId, bp.hangarAugments) { n ->
            (state.blueprints[n] as? AugmentBlueprint)?.value ?: 1f
        }

    val maxAugPage: Int get() = maxOf((augSlots - 1) / 3, 0)

    // ---------------------------------------------------------------- achievements

    private val current get() = HsHangar.variantOf(state.selectedShipId)

    /** ShowAchievementsForShip(currentShipId, currentType). */
    fun showAchievements(): Boolean {
        val c = current
        if (c != null) {
            val (b, v) = c
            return globalShowShipAchievements() && b.showShipAchievements && !(cfg.hideMissingShipAchievements && b.shipAchievements[v].isEmpty())
        }
        return globalShowShipAchievements()
    }

    private fun text(t: GameText) = t.get(state.translator)

    private fun shipClassOf(name: String): String {
        val bp = state.catalogBlueprints.getOrNull(name) as? xyz.znix.xftl.LazyShipBlueprint
        return bp?.shipClass?.let { state.translator[it] } ?: name
    }

    private fun victoryOf(type: String?, shipName: String) =
        HsVictories.achievement(type, shipName, ::shipClassOf, ::text)

    /** The ship victory icon and its name per layout: the entry's name, or the layout's when the victories are split. */
    private fun entryShip(): String? {
        val (b, v) = current ?: return null
        return if (b.splitVictoryAchievement) HsShips.variantName(b.name, v) else b.name
    }

    private fun questShip(): String? {
        val (b, v) = current ?: return null
        return if (b.splitUnlockQuestAchievement) HsShips.variantName(b.name, v) else b.name
    }

    private fun hasQuest(): Boolean {
        val (b, v) = current ?: return false
        return if (b.splitUnlockQuestAchievement) HsShipUnlocks.unlocksOf(HsShips.variantName(b.name, v)).any { it.type == HsShipUnlocks.Type.EVENT }
        else (0..2).any { HsShipUnlocks.unlocksOf(HsShips.variantName(b.name, it)).any { u -> u.type == HsShipUnlocks.Type.EVENT } }
    }

    private fun shipAchievements() = current?.let { (b, v) -> HsAchievements.shipAchievements(b.name, v, false) } ?: emptyList()

    /** The victory ending icon positions (CustomShipSelect: victoryTypes of the hangar). */
    private fun victoryIconPos(i: Int, n: Int): Pair<Int, Int> {
        val maxA = n % 6
        val maxB = n / 6
        val a = i % 6
        val b = i / 6
        return Pair(124 - 17 * (if (b == maxB) maxA else 6) + 34 * a, 437 - 17 * ((n + 5) / 6) + 34 * b)
    }

    private fun drawIcon(g: Graphics, x: Int, y: Int, size: Int, unlocked: Boolean, mode: Int, icon: String, iconLocked: String,
                         lockImage: String = "") {
        if (icon.isEmpty()) return
        if (mode < 2) {
            state.getImg("img/$icon").draw(x, y)
            if (!unlocked) {
                g.colour = Colour(0f, 0f, 0f, 0.75f); g.fillRect(x, y, size, size)
                if (lockImage.isNotEmpty()) state.getImgOrNull("img/$lockImage")?.let {
                    it.draw(x + size / 2 - it.width / 2, y + size / 2 - it.height / 2)
                }
            }
        } else {
            state.getImg("img/${if (unlocked) icon else iconLocked}").draw(x, y)
        }
        when {
            mode == 0 && size != 32 -> outline(g, x, y, size, Colour(200, 200, 200))
            mode == 1 && size != 32 -> outline(g, x, y, size, Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT)
            mode == 2 -> outline(g, x, y, size, Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT)
        }
    }

    private fun outline(g: Graphics, x: Int, y: Int, size: Int, colour: Colour) {
        g.colour = colour
        g.drawRect(x, y, size - 1, size - 1); g.drawRect(x + 1, y + 1, size - 3, size - 3)
    }

    /** The "new" label of a just-earned achievement (CAchievement::OnRender with newAchievement). */
    private fun newLabel(x: Int, y: Int, size: Int, isNew: Boolean) {
        if (!isNew) return
        font(12).printCenter(x + size / 2f, y + (if (size == 32) 17f else 47f), state.translator["new_achievement"], Colour(255, 255, 50))
    }

    private fun drawPips(x: Int, y: Int, layouts: IntArray, kind: Int, button: xyz.znix.xftl.hs.ship.ShipButtonDefinition?, shipForQuest: String) {
        val dotOn = state.getImg("img/achievements/S_V_dot_on.png")
        val dotOff = state.getImg("img/achievements/S_V_dot_off.png")
        for (i in 0 until 3) {
            val px = x + 1 + 10 * i
            val py = y + 24
            if (kind == 2) {
                if (layouts[i] == -1) {
                    if (HsShipUnlocks.unlocksOf(HsShips.variantName(shipForQuest, i)).any { it.type == HsShipUnlocks.Type.EVENT }) dotOff.draw(px, py)
                } else dotOn.draw(px, py)
            } else if (button == null || button.variantExists(i)) {
                if (layouts[i] == -1) dotOff.draw(px, py) else dotOn.draw(px, py)
            }
        }
    }

    /** The achievements part of ShipBuilder::OnRender (custom entries, then the victory-ending strip). */
    fun drawAchievements(g: Graphics, hoverSlot: String?) {
        val show = showAchievements()
        val c = current
        val head = when {
            !show -> state.translator["hangar_achievements_title_custom"]
            c == null -> state.translator["hangar_achievements_title"]
            else -> state.translator[cfg.shipAchievementsTitle]
        }
        font(13).printCenter(124f, 374f, head, Constants.TEXT_BUTTON_NORMAL)
        if (c == null) {
            drawVanillaAchievements(g, show, hoverSlot)
        } else {
            val (b, _) = c
            // Victory then quest icon (32 px, mode 3 / 2 when hovered).
            val v = victoryOf(null, entryShip()!!)
            drawIcon(g, 244, 438, 32, v.unlocked, if (hoverSlot == "hsvictory") 2 else 3, v.icon, v.iconLocked)
            if (v.unlocked && v.multiLayout) drawPips(244, 438, v.layouts, 4, b, b.name)
            if (hasQuest()) {
                val qShip = questShip()!!
                val unlocked: Boolean; val layouts = intArrayOf(-1, -1, -1); var multi = false
                if (b.splitUnlockQuestAchievement) {
                    unlocked = HsShipUnlocks.checkCustomShipUnlockQuest(qShip) > -1
                } else {
                    layouts[0] = HsShipUnlocks.checkCustomShipUnlockQuest(b.name)
                    layouts[1] = HsShipUnlocks.checkCustomShipUnlockQuest(b.name + "_2")
                    layouts[2] = HsShipUnlocks.checkCustomShipUnlockQuest(b.name + "_3")
                    unlocked = layouts.any { it > -1 }
                    multi = HsShipUnlocks.unlocksOf(b.name + "_2").any { it.type == HsShipUnlocks.Type.EVENT } ||
                        HsShipUnlocks.unlocksOf(b.name + "_3").any { it.type == HsShipUnlocks.Type.EVENT }
                }
                drawIcon(g, 244, 404, 32, unlocked, if (hoverSlot == "hsquest") 2 else 3, "achievements/S_Q_on.png", "achievements/S_Q_off.png")
                if (unlocked && multi) drawPips(244, 404, layouts, 2, b, b.name)
            }
            if (show) {
                val list = shipAchievements()
                val n = minOf(list.size, 3)
                if (n > 0) {
                    for (i in 0 until n) {
                        val a = list[i]
                        val x = 126 - 35 * n + 70 * i
                        drawIcon(g, x, 404, 64, a.unlocked, if (hoverSlot == "hsach$i") 1 else 0, a.icon, a.icon, a.lockImage)
                        newLabel(x, 404, 64, a.newAchievement)
                    }
                } else {
                    font(13).printCenter(124f, 429f, state.translator["hangar_no_ship_achievements"], Constants.TEXT_BUTTON_NORMAL)
                }
            }
        }
        if (!show) drawVictoryTypes(g, hoverSlot)
    }

    private fun drawVanillaAchievements(g: Graphics, show: Boolean, hoverSlot: String?) {
        val family = state.shipFamilies.byShipId[state.selectedShipId] ?: return
        if (show) {
            family.achievements.forEachIndexed { i, id ->
                val ach = state.achievements[id]
                val earned = state.profile.getAchievement(ach)
                val x = 21 + i * 70; val y = 404
                state.getImg(ach.img).draw(x, y)
                if (earned == null) {
                    g.colour = Colour(0f, 0f, 0f, 0.75f); g.fillRect(x, y, 64, 64)
                    state.getImg("img/customizeUI/box_lock_on.png").draw(x + 24, y + 21)
                }
                g.colour = if (hoverSlot == "achievement$i") Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT else Constants.ACHIEVEMENT_OUTLINE
                g.drawRect(x, y, 63, 63); g.drawRect(x + 1, y + 1, 61, 61)
            }
        }
        val wins = family.ships.map { state.profile.getLayoutVictory(it) }
        val on = wins.any { it != null }
        state.getImg("img/achievements/S_V_${if (on) "on" else "off"}.png").draw(244, 438)
        val dotX = 244 + (if (family.ships.size < 3) 6 else 1)
        if (on) family.ships.forEachIndexed { i, _ -> state.getImg("img/achievements/S_V_dot_${if (wins[i] != null) "on" else "off"}.png").draw(dotX + i * 10, 462) }
        if (family.hasQuest) {
            val earned = family.unlockId != null && state.profile.getShipUnlock(family) != null
            state.getImg("img/achievements/S_Q_${if (earned) "on" else "off"}.png").draw(244, 404)
        }
    }

    private fun victoryShipName(): String = current?.let { (b, v) -> if (b.splitVictoryAchievement) HsShips.variantName(b.name, v) else b.name }
        ?: state.selectedShipId.let { n -> if (n.endsWith("_2") || n.endsWith("_3")) n.dropLast(2) else n }

    private fun drawVictoryTypes(g: Graphics, hoverSlot: String?) {
        val types = HsVictories.shown()
        types.forEachIndexed { i, t ->
            val (x, y) = victoryIconPos(i, types.size)
            val v = victoryOf(t, victoryShipName())
            drawIcon(g, x, y, 32, v.unlocked, if (hoverSlot == "hsvt$i") 2 else 3, v.icon, v.iconLocked)
            if (v.unlocked && v.multiLayout) drawPips(x, y, v.layouts, 6, current?.first, "")
        }
    }

    /** ShipBuilder::MouseMove's selectedAch: the slot the mouse is over. */
    fun achievementSlot(): String? {
        val x = mouse.x - HangarLayout.achievementDx; val y = mouse.y - HangarLayout.achievementDy
        val show = showAchievements()
        val c = current
        if (c != null) {
            val (b, _) = c
            if (x > 244 && x < 276) {
                if (y > 404 && y < 436) return if (hasQuest()) "hsquest" else null
                if (y > 438 && y < 470) return "hsvictory"
            } else if (show) {
                val list = shipAchievements()
                val n = minOf(list.size, 3)
                for (i in 0 until n) {
                    val px = 126 - 35 * n + 70 * i
                    if (x > px && x < px + 64 && y > 404 && y < 468) return "hsach$i"
                }
            }
            @Suppress("UNUSED_VARIABLE") val unused = b
        } else if (show) {
            val family = state.shipFamilies.byShipId[state.selectedShipId]
            if (family != null) {
                if (y in 399 until 464) {
                    if (x in 244 until 276) return if (y >= 438) "victory" else if (family.hasQuest) "quest" else null
                    for (i in family.achievements.indices) if (x in 21 + i * 70 until 85 + i * 70) return "achievement$i"
                }
            }
        } else if (x in 244 until 276 && y in 399 until 464) {
            val family = state.shipFamilies.byShipId[state.selectedShipId]
            if (family != null) return if (y >= 438) "victory" else if (family.hasQuest) "quest" else null
        }
        if (!show) {
            val types = HsVictories.shown()
            for (i in types.indices) {
                val (px, py) = victoryIconPos(i, types.size)
                if (x > px && x < px + 32 && y > py && y < py + 32) return "hsvt$i"
            }
        }
        return null
    }

    /** AchievementTracker::SetTooltip for the custom achievements: title line, then the description and difficulty lines. */
    private fun tooltip(name: String, description: String, unlocked: Boolean, multiDifficulty: Boolean, multiLayout: Boolean,
                        layouts: IntArray, difficulty: Int): String {
        var tip = description
        if (unlocked && multiDifficulty) {
            tip += "\n"
            if (multiLayout) {
                for (i in 0 until 3) if (layouts[i] > -1) {
                    tip += "\n" + state.translator["achieved_with"].replace("\\1", state.translator["type_" + ('a' + i)])
                        .replace("\\2", state.translator[listOf("easy", "normal", "hard")[layouts[i].coerceIn(0, 2)]])
                }
            } else when (difficulty) {
                0 -> tip += "\n" + state.translator["ach_easy"]
                1 -> tip += "\n" + state.translator["ach_normal"]
                2 -> tip += "\n" + state.translator["ach_hard"]
            }
        }
        return name + "\n" + tip
    }

    /** The tooltip text (title first line) of an HS achievement slot, null when not an HS slot. */
    fun achievementHint(slot: String): String? {
        val c = current
        when {
            slot == "hsvictory" -> {
                val v = victoryOf(null, entryShip() ?: return null)
                return tooltip(v.name, v.description, v.unlocked, true, v.multiLayout, v.layouts, v.difficulty)
            }
            slot == "hsquest" -> {
                val (b, _) = c ?: return null
                val qShip = questShip() ?: return null
                val cls = shipClassOf(qShip)
                val name = state.translator["quest_ach_title"].replace("\\1", cls)
                val desc = state.translator["quest_ach"].replace("\\1", cls)
                val layouts = intArrayOf(-1, -1, -1); var diff = -1; val unlocked: Boolean; var multi = false
                if (b.splitUnlockQuestAchievement) { diff = HsShipUnlocks.checkCustomShipUnlockQuest(qShip); unlocked = diff > -1 }
                else {
                    layouts[0] = HsShipUnlocks.checkCustomShipUnlockQuest(b.name); layouts[1] = HsShipUnlocks.checkCustomShipUnlockQuest(b.name + "_2")
                    layouts[2] = HsShipUnlocks.checkCustomShipUnlockQuest(b.name + "_3")
                    unlocked = layouts.any { it > -1 }
                    multi = HsShipUnlocks.unlocksOf(b.name + "_2").any { it.type == HsShipUnlocks.Type.EVENT } ||
                        HsShipUnlocks.unlocksOf(b.name + "_3").any { it.type == HsShipUnlocks.Type.EVENT }
                    if (!multi) diff = layouts[0]
                }
                return tooltip(name, desc, unlocked, true, multi, layouts, diff)
            }
            slot.startsWith("hsach") -> {
                val a = shipAchievements().getOrNull(slot.removePrefix("hsach").toInt()) ?: return null
                val name = a.shownName?.let { text(it) } ?: ""
                val desc = a.shownDescription?.let { text(it) } ?: ""
                return tooltip(name, desc, a.unlocked, a.multiDifficulty, false, intArrayOf(-1, -1, -1), a.difficulty)
            }
            slot.startsWith("hsvt") -> {
                val types = HsVictories.shown()
                val t = types.getOrNull(slot.removePrefix("hsvt").toInt()) ?: return null
                val v = victoryOf(t, victoryShipName())
                return tooltip(v.name, v.description, v.unlocked, true, v.multiLayout, v.layouts, v.difficulty)
            }
        }
        return null
    }

    /** ShipBuilder::MouseClick: the trophy toggles the ship achievements when `toggle` is set. */
    fun click(x: Int, y: Int): Boolean {
        val ax = x - HangarLayout.achievementDx; val ay = y - HangarLayout.achievementDy
        if (cfg.shipAchievementsToggle && ax > 244 && ax < 276 && ay > 438 && ay < 470) {
            val on = !globalShowShipAchievements()
            showShipAchievementsToggle = on
            state.sounds.getSample(if (on) "powerDownSystem" else "powerUpSystem").play()
            return true
        }
        if (maxAugPage > 0 && !state.isShipListVisible) {
            if (arrowHover(1238 + pagerDx, 575 + pagerDy, "up")) { augPage = if (augPage == 0) maxAugPage else augPage - 1; return true }
            if (arrowHover(1238 + pagerDx, 629 + pagerDy, "down")) { augPage = if (augPage == maxAugPage) 0 else augPage + 1; return true }
        }
        return false
    }

    private fun arrowHover(x: Int, y: Int, name: String): Boolean = arrowAt(x, y, name, mouse.x, mouse.y)

    private fun arrowAt(x: Int, y: Int, name: String, px: Int, py: Int): Boolean {
        val img = state.getImgOrNull("img/statusUI/button_crew_${name}_on.png") ?: return false
        return px >= x && px < x + img.width && py >= y && py < y + img.height
    }

    /** What a finger taps (TouchRouter): the trophy toggle, an augment pager arrow; else null. */
    fun liftTarget(x: Int, y: Int): Any? {
        val ax = x - HangarLayout.achievementDx; val ay = y - HangarLayout.achievementDy
        if (cfg.shipAchievementsToggle && ax > 244 && ax < 276 && ay > 438 && ay < 470) return "hs-trophy"
        if (maxAugPage > 0 && !state.isShipListVisible) {
            if (arrowAt(1238 + pagerDx, 575 + pagerDy, "up", x, y)) return "hs-aug-up"
            if (arrowAt(1238 + pagerDx, 629 + pagerDy, "down", x, y)) return "hs-aug-down"
        }
        return null
    }

    // ---------------------------------------------------------------- equipment

    /** The augment boxes shown on the current page (global slot index). */
    fun augmentRange(): IntRange { checkShip(); return augPage * 3 until minOf(augSlots, (augPage + 1) * 3) }

    fun drawAugmentPager(g: Graphics) {
        checkShip()
        if (maxAugPage <= 0) return
        font(62).printRight(1256f + pagerDx, 489f + pagerDy, "${augPage + 1}/${maxAugPage + 1}", Constants.JUMP_DISABLED_TEXT)
        for ((x, y, name) in listOf(Triple(1238 + pagerDx, 575 + pagerDy, "up"), Triple(1238 + pagerDx, 629 + pagerDy, "down"))) {
            val over = arrowHover(x, y, name) && !state.isShipListVisible
            state.getImgOrNull("img/statusUI/button_crew_${name}_${if (over) "select2" else "on"}.png")?.draw(x, y)
        }
    }

    /** Dummy weapon/drone slots of a ship without the system, at the configured opacity. */
    fun dummySlots(): Boolean = cfg.showDummyEquipmentSlots

    fun dummyOpacity(): Float = cfg.dummyEquipmentSlotsOpacity

    // ---------------------------------------------------------------- reactor and counts

    val reactorHover: Boolean
        get() {
            if (!HsMoreInfo.showReactor() || state.isShipListVisible) return false
            val img = state.getImg("img/customizeUI/box_system_on.png")
            return mouse.x >= REACTOR_X && mouse.x < REACTOR_X + img.width && mouse.y >= reactorY && mouse.y < reactorY + img.height
        }

    /** The reactor box, then the missile, drone and crew-limit counts (the tail of ShipBuilder::OnRender). */
    fun drawExtras(g: Graphics) {
        val d = HsShips.def(state.selectedShipId)
        if (HsMoreInfo.showReactor()) {
            val over = reactorHover
            state.getImg("img/customizeUI/box_system_${if (over) "select2" else "on"}.png").draw(REACTOR_X, reactorY)
            state.getImg("img/icons/s_reactor_green1.png").draw(REACTOR_X - 13, reactorY + 45)
            val green = Colour(100, 255, 100)
            font(52).printCenter(REACTOR_X + 18f, reactorY + 25f, bp.startingReactorPower.toString(), green)
            font(52).printCenter(REACTOR_X + 18f, reactorY + 43f, d.maxReactorLevel.toString(), green)
            font(52).printCenter(REACTOR_X + 19f, reactorY + 27f, "_", green)
        }
        if (cfg.showMissileCount) {
            state.getImg("img/customizeUI/shipresources_missiles_box.png").draw(880 + weaponsDx, 484 + pagerDy)
            font(0).printCenter(923f + weaponsDx, 494f + pagerDy, bp.initialMissiles.toString(), Colour.white)
        }
        if (cfg.showDroneCount) {
            state.getImg("img/customizeUI/shipresources_drones_box.png").draw(880 + weaponsDx, 594 + pagerDy)
            font(0).printCenter(923f + weaponsDx, 604f + pagerDy, bp.initialDroneParts.toString(), Colour.white)
        }
        if (cfg.showCrewLimit) {
            state.getImg("img/customizeUI/shipresources_crewlimit_box.png").draw(314 + crewDx, 484 + pagerDy)
            font(0).printCenter(357f + crewDx, 494f + pagerDy, d.crewLimit.toString(), Colour.white)
        }
    }

    /** The reactor InfoBox (title, text) while hovered; the text follows ShipBuilder::MouseMove. */
    fun reactorInfo(): Pair<String, String>? {
        if (!reactorHover) return null
        val t = state.translator
        val d = HsShips.def(state.selectedShipId)
        val text = t["reactor_desc"] + "\n\n" + t["reactor_desc_start"].replace("\\1", bp.startingReactorPower.toString()) + "\n" +
            t["reactor_desc_max"].replace("\\1", d.maxReactorLevel.toString())
        return Pair(t["upgrade_reactor"], text)
    }
}
