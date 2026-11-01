// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (CrewControl::KeyDown,
// CrewBox constructor / OnLoop / OnRender / MouseClick power buttons), src/game/Gameplay/World/CrewAbilities.cpp
// (GetCrewBoxResourceWidth, CrewBox_Extend::GetTotalCooldownWidth / EmplacePower),
// src/game/UserInterface/CrewBox_Extend.cpp (CrewAbilityCooldownBar / CrewAbilityChargesBar::OnRender),
// CustomCrew.cpp CrewBox::GetSelected (power button tooltips), src/game/Ship/CrewMember_Extend.cpp (tooltip names)
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.crew.AbstractCrew

/** The player's ways to use a crew power: the lockdown hotkey and the crew box power buttons and bars. */
object HsCrewUi {
    /**
     * CrewControl::KeyDown for the "lockdown" hotkey: for each selected crew member with a special power, every
     * ready HOTKEY_ALWAYS power, then the first ready HOTKEY_FIRST power (HS runs the second loop even when the
     * first activated something).
     */
    @JvmStatic
    fun lockdownHotkey(selected: Collection<AbstractCrew>) {
        for (c in selected.toList()) {
            val ex = HsCrew.ex(c)
            if (!ex.hasSpecialPower) continue
            for (p in ex.crewPowers) {
                if (!p.enabled) continue
                if (p.def.onHotkey == PowerConst.HOTKEY_ALWAYS && p.powerReady() == PowerReadyState.READY && ex.powerOwner == 0)
                    p.preparePower()
            }
            for (p in ex.crewPowers) {
                if (!p.enabled) continue
                if (p.def.onHotkey == PowerConst.HOTKEY_FIRST && p.powerReady() == PowerReadyState.READY && ex.powerOwner == 0) {
                    p.preparePower()
                    break
                }
            }
        }
    }

    /** CrewBox::OnLoop: a power button is active for a player-owned, living crew member whose power is ready. */
    @JvmStatic
    fun buttonActive(c: AbstractCrew, p: ActivatedPower): Boolean =
        HsCrew.ex(c).powerOwner == 0 && p.powerReady() == PowerReadyState.READY && !HsCrew.isDead(c)

    /** CrewBox::MouseClick on an active, hovered power button. */
    @JvmStatic
    fun clickButton(c: AbstractCrew, p: ActivatedPower) {
        if (p.powerReady() == PowerReadyState.READY && HsCrew.ex(c).powerOwner == 0) p.preparePower()
    }

    private val EXTRA_TRUE = arrayOf(
        "power_not_ready_burning", "power_not_ready_suffocating", "power_not_ready_mind", "power_not_ready_stunned",
        "power_not_ready_repairing", "power_not_ready_repairing_system", "power_not_ready_repairing_breach",
        "power_not_ready_fighting", "power_not_ready_sabotaging", "power_not_ready_shooting", "power_not_ready_moving",
        "power_not_ready_idle", "power_not_ready_manning", "power_not_ready_firefighting", "power_not_ready_dying",
        "power_not_ready_teleporting",
    )

    private val STATE_NAMES = mapOf(
        PowerReadyState.NOT_READY_COOLDOWN to "power_not_ready",
        PowerReadyState.NOT_READY_ACTIVATED to "power_not_ready_activated",
        PowerReadyState.NOT_READY_PLAYER_SHIP to "power_not_ready_player_ship",
        PowerReadyState.NOT_READY_ENEMY_SHIP to "power_not_ready_enemy_ship",
        PowerReadyState.NOT_READY_ENEMY_IN_ROOM to "power_not_ready_enemy_in_room",
        PowerReadyState.NOT_READY_FRIENDLY_IN_ROOM to "power_not_ready_friendly_in_room",
        PowerReadyState.NOT_READY_WHITELIST to "power_not_ready_whitelist",
        PowerReadyState.NOT_READY_ENEMY_WHITELIST to "power_not_ready_enemy_whitelist",
        PowerReadyState.NOT_READY_FRIENDLY_WHITELIST to "power_not_ready_friendly_whitelist",
        PowerReadyState.NOT_READY_ENEMY_BLACKLIST to "power_not_ready_enemy_blacklist",
        PowerReadyState.NOT_READY_FRIENDLY_BLACKLIST to "power_not_ready_friendly_blacklist",
        PowerReadyState.NOT_READY_SYSTEM_IN_ROOM to "power_not_ready_system_in_room",
        PowerReadyState.NOT_READY_SYSTEM_DAMAGED to "power_not_ready_system_damaged",
        PowerReadyState.NOT_READY_AI_DISABLED to "power_not_ready_ai_disabled",
        PowerReadyState.NOT_READY_HAS_CLONEBAY to "power_not_ready_has_clonebay",
        PowerReadyState.NOT_READY_OUT_OF_COMBAT to "power_not_ready_out_of_combat",
        PowerReadyState.NOT_READY_IN_COMBAT to "power_not_ready_in_combat",
        PowerReadyState.NOT_READY_SYSTEM to "power_not_ready_system",
        PowerReadyState.NOT_READY_SYSTEM_FUNCTIONAL to "power_not_ready_system_functional",
        PowerReadyState.NOT_READY_MIN_HEALTH to "power_not_ready_min_health",
        PowerReadyState.NOT_READY_MAX_HEALTH to "power_not_ready_max_health",
        PowerReadyState.NOT_READY_CHARGES to "power_not_ready_charges",
        PowerReadyState.NOT_READY_SILENCED to "power_not_ready_silenced",
    )

    /**
     * CrewBox::GetSelected over a power button: the tooltip text. Enemy-owned (mind-controlled) crew get
     * power_not_ready_mind_enemy; otherwise POWER_TOOLTIP(power, state) (Lua) may supply the text, else the power's
     * tooltip (ready) or the not-ready reason for the state (the charge requirements' state while cooling down with
     * a chargeReq). The charges line and, for a ready power, the lockdown hotkey line are always appended (HS's
     * skipAppend from the callback lands in an inner variable). [systemTitle] maps a system id to its title.
     */
    @JvmStatic
    fun powerTooltip(c: AbstractCrew, p: ActivatedPower, tr: (String) -> String, hotkey: String,
                     systemTitle: (Int) -> String?): String {
        val ex = HsCrew.ex(c)
        var tooltip = ""
        var appendHotkey = false
        if (ex.powerOwner != 0) {
            tooltip = tr("power_not_ready_mind_enemy")
        } else {
            var state = p.powerReady()
            if (state == PowerReadyState.READY) {
                appendHotkey = true
                val lua = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.powerTooltip(p, state) else null
                if (lua != null) tooltip = lua
                else if (p.def.tooltip.data.isNotEmpty()) tooltip = text(p.def.tooltip, tr)
            } else {
                var req = p.def.playerReq
                val chargeReq = p.def.chargeReq
                if (state == PowerReadyState.NOT_READY_COOLDOWN && chargeReq != null) {
                    req = chargeReq
                    state = p.powerReq(req)
                    if (state == PowerReadyState.READY) state = PowerReadyState.NOT_READY_COOLDOWN
                }
                val lua = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.powerTooltip(p, state) else null
                if (lua != null) {
                    tooltip = lua
                } else {
                    var name = STATE_NAMES[state] ?: ""
                    var replace = ""
                    when (state) {
                        PowerReadyState.NOT_READY_SYSTEM, PowerReadyState.NOT_READY_SYSTEM_FUNCTIONAL ->
                            replace = systemTitle(req.requiredSystem) ?: ""
                        PowerReadyState.NOT_READY_MIN_HEALTH -> replace = req.minHealth.value.toString()
                        PowerReadyState.NOT_READY_MAX_HEALTH -> replace = req.maxHealth.value.toString()
                        PowerReadyState.NOT_READY_EXTRACONDITION_OR -> tooltip = text(req.extraOrConditionsTooltip, tr)
                        else -> if (state >= PowerReadyState.NOT_READY_EXTRACONDITION_FALSE) {
                            name = EXTRA_TRUE.getOrNull(state - PowerReadyState.NOT_READY_EXTRACONDITION_FALSE)?.let { it + "_false" } ?: ""
                        } else if (state >= PowerReadyState.NOT_READY_EXTRACONDITION_TRUE) {
                            name = EXTRA_TRUE.getOrNull(state - PowerReadyState.NOT_READY_EXTRACONDITION_TRUE) ?: ""
                        }
                    }
                    if (name.isNotEmpty()) {
                        tooltip = tr(name)
                        if (replace.isNotEmpty()) tooltip = tooltip.replace("\\1", replace)
                    }
                }
            }
        }
        var s = tooltip
        if (p.def.powerCharges > -1) {
            s += "\n" + tr("power_number_charges").replace("\\1", p.powerCharges.first.toString())
                .replace("\\2", p.powerCharges.second.toString())
        }
        if (appendHotkey) s += "\n" + tr("hotkey").replace("\\1", hotkey)
        return s
    }

    private fun text(t: HsText, tr: (String) -> String): String = if (t.isLiteral) t.data else tr(t.data)

    /** The button label: the power's buttonLabel, else the native CrewBox powerButton's "button_lockdown". */
    @JvmStatic
    fun buttonLabel(p: ActivatedPower, translate: (String) -> String): String {
        val l = p.def.buttonLabel
        if (l.data.isEmpty()) return translate("button_lockdown")
        return if (l.isLiteral) l.data else translate(l.data)
    }

    // ------------------------------------------------------------------ crew box layout (CrewBox constructor hook)

    private val MAX_COOLDOWN_WIDTH = intArrayOf(12, 18, 18, 22, 999)
    private val COOLDOWN_WIDTHS = intArrayOf(4, 3, 2, 2, 1)
    private val CHARGES_WIDTHS = intArrayOf(3, 2, 2, 1, 1)

    /** A cooldown or charges bar: [power] or [resource] (+ the temporary powers [more] a resource bar shows). */
    class Bar(
        @JvmField val power: ActivatedPower?, @JvmField val resource: ActivatedPowerResource?,
        @JvmField val x: Int, @JvmField val y: Int, @JvmField val w: Int, @JvmField val h: Int
    ) {
        @JvmField var more: List<ActivatedPower> = emptyList()
    }

    /**
     * The HS crew box of a crew member with a special power. [startX] is where the box now starts (relative to
     * box.x, <= 0), [crewPosDx] moves the portrait right, [borders] are x offsets (from box.x) of the dividers right
     * of the ability bars, [skillOffset] pushes the skill bars down below the [buttons].
     */
    class BoxLayout(
        @JvmField val startX: Int, @JvmField val crewPosDx: Int, @JvmField val skillOffset: Int,
        @JvmField val buttons: List<ActivatedPower>, @JvmField val cooldownBars: List<Bar>,
        @JvmField val chargesBars: List<Bar>, @JvmField val borders: List<Int>
    )

    private fun hasCd(p: ActivatedPower) = !p.def.hideCooldown && p.powerCooldown.second > 0f
    private fun hasCh(p: ActivatedPower) = !p.def.hideCharges && p.powerCharges.second > 0

    /** ActivatedPowerResource::GetLinkedPowers: the enabled powers using [r]. */
    private fun linkedPowers(ex: CrewExtend, r: ActivatedPowerResource): List<ActivatedPower> =
        ex.crewPowers.filter { p -> p.enabled && p.powerResources.any { it === r } }

    /** ActivatedPower::GetCrewBoxResourceWidth. */
    private fun powerWidth(p: ActivatedPower, mode: Int): Int {
        val w = (if (hasCd(p)) COOLDOWN_WIDTHS[mode] + 1 else 0) + (if (hasCh(p)) CHARGES_WIDTHS[mode] + 1 else 0)
        return if (w > 0) w + 3 else 0
    }

    /** ActivatedPowerResource::GetCrewBoxResourceWidth. */
    private fun resourceWidth(r: ActivatedPowerResource, linked: List<ActivatedPower>, mode: Int): Int {
        var cd = if (!r.def.hideCooldown && r.powerCooldown.second > 0f) 1 else 0
        var ch = if (!r.def.hideCharges && r.powerCharges.second > 0) 1 else 0
        if (r.def.showLinkedCooldowns || r.def.showLinkedCharges) for (p in linked) {
            if (r.def.showLinkedCooldowns && p.powerCooldown.second > 0f) cd++
            if (r.def.showLinkedCharges && p.powerCharges.second > 0) ch++
        }
        val w = cd * (COOLDOWN_WIDTHS[mode] + 1) + ch * (CHARGES_WIDTHS[mode] + 1)
        return if (w > 0) w + 3 else 0
    }

    /** The layout for [c]'s box at ([boxX], [boxY]) of size [boxW] x [boxH], or null for a box HS leaves alone. */
    @JvmStatic
    fun layout(c: AbstractCrew, boxX: Int, boxY: Int, boxH: Int): BoxLayout? {
        if (!CustomCrewManager.isRace(c.codename)) return null
        val ex = HsCrew.ex(c)
        if (!ex.hasSpecialPower) return null
        val buttons = ex.crewPowers.filter { it.enabled && !it.def.hideButton }
        val linked = HashMap<ActivatedPowerResource, List<ActivatedPower>>()
        for (r in ex.powerResources)
            if (r.enabled && (r.def.showTemporaryBars || r.def.showLinkedCooldowns || r.def.showLinkedCharges))
                linked[r] = linkedPowers(ex, r)
        var mode = 0
        var width = 0
        while (mode < 5) {
            width = 0
            for (p in ex.crewPowers) if (p.enabled) width += powerWidth(p, mode)
            for (r in ex.powerResources) if (r.enabled) width += resourceWidth(r, linked[r] ?: emptyList(), mode)
            if (width <= MAX_COOLDOWN_WIDTH[mode]) break
            mode++
        }
        // The loop can only end with mode 5 if the 999 limit is exceeded; HS would then index past its arrays.
        val m = minOf(mode, 4)
        val startX = maxOf(-13, if (width > 10) 5 - width else -width / 2)
        val crewPosDx = minOf(startX + width, maxOf(2, startX + width - 3))
        var offset = startX
        val cdBars = ArrayList<Bar>()
        val chBars = ArrayList<Bar>()
        val borders = ArrayList<Int>()
        val cw = COOLDOWN_WIDTHS[m]
        val chw = CHARGES_WIDTHS[m]
        fun bar(p: ActivatedPower?, r: ActivatedPowerResource?, w: Int) = Bar(p, r, boxX + offset + 3, boxY + 3, w, boxH - 6)

        fun emplace(p: ActivatedPower) {
            if (!p.enabled) return
            val cd = hasCd(p)
            val ch = hasCh(p)
            if (!(cd || ch)) return
            if (ch) {
                chBars += bar(p, null, chw); offset += chw + 1
            }
            if (cd) {
                cdBars += bar(p, null, cw); offset += cw + 1
            }
            borders += offset + 4
            offset += 3
        }

        fun emplace(r: ActivatedPowerResource) {
            if (!r.enabled) return
            val cd = !r.def.hideCooldown && r.powerCooldown.second > 0f
            val ch = !r.def.hideCharges && r.powerCharges.second > 0
            val old = offset
            val lp = linked[r] ?: emptyList()
            if (r.def.showLinkedCharges) for (p in lp) if (p.powerCharges.second > 0) {
                chBars += bar(p, null, chw); offset += chw + 1
            }
            if (ch) {
                chBars += bar(null, r, chw); offset += chw + 1
            }
            if (r.def.showLinkedCooldowns) for (p in lp) if (p.powerCooldown.second > 0f) {
                cdBars += bar(p, null, cw); offset += cw + 1
            }
            if (cd) {
                val b = bar(null, r, cw)
                if (r.def.showTemporaryBars) b.more = lp
                cdBars += b
                offset += cw + 1
            }
            if (offset != old) {
                borders += offset + 4; offset += 3
            }
        }

        // Powers and resources merged by sortOrder (a power first on equal order).
        var i = 0
        var j = 0
        val powers = ex.crewPowers
        val resources = ex.powerResources
        while (i < powers.size || j < resources.size) {
            if (i < powers.size && (j >= resources.size || powers[i].def.sortOrder <= resources[j].def.sortOrder))
                emplace(powers[i++])
            else emplace(resources[j++])
        }
        return BoxLayout(startX, crewPosDx, buttons.size * 24, buttons, cdBars, chBars, borders)
    }

    /** CrewAbilityCooldownBar::OnRender: the filled part as (x, y, w, h) and its colour, or null. */
    @JvmStatic
    fun cooldownFill(b: Bar): Pair<IntArray, HsColour>? {
        val cooldown: FloatPair
        val colour: HsColour
        val p = b.power
        if (p != null) {
            if (p.temporaryPowerActive) {
                cooldown = p.temporaryPowerDuration; colour = p.def.tempPower.cooldownColour
            } else {
                cooldown = p.powerCooldown; colour = p.def.cooldownColour
            }
        } else {
            val t = b.more.firstOrNull { it.temporaryPowerActive }
            val r = b.resource!!
            if (t != null) {
                cooldown = t.temporaryPowerDuration; colour = t.def.tempPower.cooldownColour
            } else {
                cooldown = r.powerCooldown; colour = r.def.cooldownColour
            }
        }
        val h = (cooldown.first / cooldown.second * b.h).toInt()
        if (h <= 0) return null
        return intArrayOf(b.x, b.h - h + b.y, b.w, h) to colour
    }

    /** CrewAbilityChargesBar::OnRender: the filled rectangles (x, y, w, h each) and their colour, or null. */
    @JvmStatic
    fun chargesFill(b: Bar): Pair<List<IntArray>, HsColour>? {
        val charges = b.power?.powerCharges ?: b.resource!!.powerCharges
        val colour = b.power?.def?.cooldownColour ?: b.resource!!.def.cooldownColour
        if (charges.first <= 0 || charges.second <= 0) return null
        if (charges.second * 3 - 1 > b.h) {
            val h = maxOf(1, b.h * charges.first / charges.second)
            return listOf(intArrayOf(b.x, b.h - h + b.y, b.w, h)) to colour
        }
        val gap = 1
        val maxH = b.h - gap * (charges.second - 1)
        val seg = maxH / charges.second
        val rem = maxH % charges.second
        var y0 = 0
        val out = ArrayList<IntArray>()
        for (i in 0 until minOf(charges.first, charges.second + 1)) {
            var y1 = y0 + seg
            if (i < rem) y1++
            out += intArrayOf(b.x, b.y + b.h - y1, b.w, y1 - y0)
            y0 = y1 + gap
        }
        return out to colour
    }
}
