package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.VanillaHotkeys
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.ITooltipProvider
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.Weapons
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** WeaponBlueprint::GetDescription ends each line of a weapon tooltip with a space and a newline. */
private const val LINE_END = " \n"

abstract class AbstractWeaponInstance(type: AbstractWeaponBlueprint, val ship: Ship) {
    var type: AbstractWeaponBlueprint = type
        private set
    /** ProjectileFactory::numShots and iRequiredPower: copied from the blueprint when the weapon is built. */
    @JvmField var hsNumShots: Int = type.shots
    @JvmField var hsRequiredPower: Int = type.power
    /** ProjectileFactory::blueprint written with a blueprint of another weapon family: the goal follows it, the projectile kind stays. */
    @JvmField var hsForeignBlueprint: AbstractWeaponBlueprint? = null

    fun hsSetBlueprint(b: AbstractWeaponBlueprint) {
        if (b.javaClass == type.javaClass) { type = b; hsForeignBlueprint = null } else hsForeignBlueprint = b
    }

    // The time spent charging thus far
    var timeCharged: Float = 0f

    /** WeaponSystem::userPowered: the player powered this weapon (WeaponSystem::PowerWeapon with userDriven). */
    @JvmField var hsUserPowered = false

    // For charger weapons, this is how many shots are stored up, in
    // addition to the one represented by timeCharged.
    var extraCharges: Int = 0

    // For chain weapons, this is the number of volleys we've fired since
    // the weapon was powered off. This is limited to the maximum number
    // of shots that have an effect.
    var chainCount: Int = 0

    // Native ProjectileFactory counts volleys, not individual burst projectiles.
    // null means an older save did not retain this encounter's firing history.
    var browserEncounterVolleys: Int? = 0
        private set

    // Native SetCurrentShip (0x0040ccc0) clears the count only when the weapon
    // system receives a different non-null target ship, i.e. a new encounter.
    // Its callers are the weapon-system target setters, so a volley aimed at a
    // different ship (a bomb fired at our own ship) still accumulates here.
    fun browserResetEncounterVolleys() {
        browserEncounterVolleys = 0
        hsShotsFired = 0
    }

    /**
     * Hyperspace: ProjectileFactory::shotsFiredAtTarget as HS counts it (ProjectileFactory::Update rewrite: one per
     * volley, at the moment it starts), for `<shotLimit>`; cleared with [browserEncounterVolleys]. Saved only while
     * Hyperspace is active (HS adds it to ProjectileFactory::SaveState).
     */
    @JvmField var hsShotsFired: Int = 0
    /** ProjectileFactory::iSpendMissile: the missiles the volley just fired will spend (0 once spent). */
    @JvmField var hsSpendMissile: Int = 0

    /** A Lua write of ProjectileFactory::iAmmo, read back until the next ship loop copies the missile count again. */
    @JvmField var hsLuaAmmo: Int? = null

    /** ProjectileFactory::SpendMissiles as Hyperspace changes it (free missiles, replicator roll). */
    fun hsSpendMissiles() {
        val n = hsSpendMissile
        hsSpendMissile = 0
        if (!ship.sys.debugFlags.infiniteMissiles.set && n > 0 && xyz.znix.xftl.hs.weapons.HsWeapons.spendsMissiles(type)) {
            val spent = xyz.znix.xftl.hs.weapons.HsWeapons.spendMissiles(this, n)
            ship.missilesCount = max(0, ship.missilesCount - spent)
        }
    }

    /** ProjectileFactory::SetHacked from Lua: iHackLevel until WeaponSystem::OnLoop sets it again (next frame). */
    @JvmField var hsLuaHackLevel: Int? = null

    /** Hyperspace: the cooldown modifier last set through WEAPON_COOLDOWN_MOD (ProjectileFactory::cooldownModifier). */
    @JvmField var hsCooldownMod = 1f
    /** ProjectileFactory::currentFiringAngle as a script wrote it. */
    @JvmField var hsFiringAngle: Double? = null

    /**
     * ProjectileFactory::radius: the blueprint's radius when the weapon is made. A weapon targeted at a room with a
     * radius shows a red disc of that radius around its crosshairs; Hyperspace scripts may change it.
     */
    @JvmField var aimRadius: Int = type.blueprintRadius

    /**
     * Hyperspace Lua `ProjectileFactory.lastTargets`: the aim ProjectileFactory::Fire last copied into lastTargets,
     * dropped when the weapon is untargeted (its shot, ClearAiming, power loss).
     */
    @JvmField var hsLastAim: Any? = null

    /** Hyperspace Lua: a value a script wrote into `ProjectileFactory.autoFiring` (null: none). */
    @JvmField var hsAutoFiringWritten: Boolean? = null

    protected fun browserRecordVolley(target: Ship) {
        if (ship.sys.isCurrentlyLoadingSave) return
        browserEncounterVolleys = browserEncounterVolleys?.let { it + 1 }
        ftl.browser.BrowserDebug.record("achievement", "weapon.volley",
            "{\"weapon\":${ftl.browser.BrowserDebug.quote(type.name)},\"player\":${ship.isPlayerShip},\"self\":${target === ship},\"volleys\":$browserEncounterVolleys}")
    }

    // The number of charges this weapon is ready to fire.
    val totalReadyCharges: Int get() = extraCharges + if (isCurrentCharged) 1 else 0

    // Is this weapon selected as powered
    // To set this, use forceSetPowered and read it's JavaDoc.
    var isPowered: Boolean = false
        private set

    /** ProjectileFactory::powered written by a script: the flag alone. */
    fun hsWritePowered(v: Boolean) { isPowered = v }

    /**
     * Per-weapon autofire override: null follows the ship's global autofire
     * setting, true/false forces it on/off for this weapon. Toggled with
     * ctrl + aim (or ctrl + weapon hotkey), like vanilla.
     */
    var autofireOverride: Boolean? = null

    abstract val isFiring: Boolean

    // Turn missile weapons off once their ship runs out of missiles
    val hasEnoughMissiles: Boolean get() = ship.missilesCount >= type.missilesUsed

    // How far out is this weapon, on a scale of 0-1. Used for making weapons slide in and out when
    // powered up and down.
    var slide: Float = 0f

    /**
     * True if the weapon is ready to fire. For charge weapons this is true
     * even when [isCurrentCharged] is false, as long as they've stored away an extra shot.
     */
    val isCharged: Boolean get() = isCurrentCharged || extraCharges > 0

    /**
     * True if the weapon's current charge has finished charging. This is different
     * to [isCharged] for charge weapons, as this is only true when the currently
     * charging charge is done.
     */
    val isCurrentCharged: Boolean get() = timeCharged >= chargeTime && !hsFullByScript

    /**
     * A script set the cooldown to full: the bar is full but the charge level only rises when Update reaches the goal,
     * so the weapon doesn't count as charged (oracle hw-fr).
     */
    @JvmField var hsFullByScript = false

    fun hsWriteCharge(v: Float) {
        val value = v.coerceIn(0f, chargeTime)
        if (value >= chargeTime) { if (timeCharged < chargeTime) hsFullByScript = true } else hsFullByScript = false
        timeCharged = value
    }
    val chargeProgress: Float get() = timeCharged / chargeTime

    val maxTotalCharges get() = type.chargeLevels ?: 1
    val maxExtraCharges get() = maxTotalCharges - 1

    val animation = type.getLauncher(ship.sys)

    private val boostAnim = animation.boostAnim?.startSingle(ship.sys)

    /**
     * True if the player can fire this weapon at their own ship, eg for bombs.
     */
    open val canTargetOwnShip: Boolean get() = false

    val chargeTime: Float
        get() {
            val type = hsForeignBlueprint ?: type
            val boost = type.boost
            if (boost?.type != AbstractWeaponBlueprint.BoostType.COOLDOWN)
                return type.chargeTime

            return type.chargeTime - chainCount * boost.perShot
        }

    /**
     * The tooltip displayed when the player hovers the button they click to aim the weapon.
     */
    val weaponBarTooltip: ITooltipProvider?
        get() {
            // Hyperspace's WeaponBox::GenerateTooltip hook reads redesignedWeaponTooltips.currentValue each time it
            // builds the text, so a More Info toggle changes the next tooltip: rebuilt when that value changed.
            val redesigned = xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.render.HsMoreInfo.redesignedWeaponTooltips()
            val cached = barTooltip
            if (cached != null && redesigned == barTooltipRedesigned) return cached
            barTooltipRedesigned = redesigned
            return createWeaponBarTooltip().also { barTooltip = it }
        }
    private var barTooltip: ITooltipProvider? = null
    private var barTooltipRedesigned = false

    init {
        // Hyperspace ProjectileFactory constructor (after super): CONSTRUCT_PROJECTILE_FACTORY.
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.weapons.HsWeaponsLua.constructFactory(this)
    }


    /** WeaponAnimation::mountPoint moved by a script: the weapon draws around the written point. */
    /** Hyperspace Lua: WeaponAnimation::fireLocation moved by a script. */
    @JvmField var hsFireShiftX = 0
    @JvmField var hsFireShiftY = 0
    @JvmField var hsMountShiftX = 0
    @JvmField var hsMountShiftY = 0

    /** ProjectileFactory::subCooldown: (0, 0) unless a script writes it. */
    @JvmField var hsSubCooldownFirst = 0f
    @JvmField var hsSubCooldownSecond = 0f

    open fun update(dt: Float, chargeTime: Float, canCharge: Boolean) {
        // ProjectileFactory::SpendMissiles runs every loop: a script's iSpendMissile is spent like a shot's.
        if (hsSpendMissile > 0 && xyz.znix.xftl.hs.HS.active) hsSpendMissiles()
        val speedMult = (1f + ship.getAugmentValue(AugmentBlueprint.AUTOMATED_RELOADERS))

        // ProjectileFactory::Update (0x40dc80): charging runs at
        // AUTO_COOLDOWN + 1 per second; a hacking pulse (negative
        // [chargeTime]) drains one per second and an unpowered weapon six,
        // neither sped up by reloaders.
        if (isPowered) {
            // ProjectileFactory::subCooldown (HS CustomWeapons.cpp): while first is under second neither a charge nor a hack's drain runs.
            if (hsSubCooldownFirst < hsSubCooldownSecond) {
                // A script set it; only scripts move it.
            } else if (chargeTime < 0f)
                timeCharged += chargeTime
            else if (canCharge)
                timeCharged += chargeTime * speedMult
        } else {
            timeCharged -= abs(chargeTime) * 6f

            if (timeCharged <= 0) {
                extraCharges = 0
                chainCount = 0
            }
        }

        if (timeCharged > this.chargeTime)
            timeCharged = this.chargeTime
        if (timeCharged < 0f)
            timeCharged = 0f
        if (timeCharged < this.chargeTime)
            hsFullByScript = false

        if (isCurrentCharged && extraCharges < maxExtraCharges) {
            timeCharged = 0f
            extraCharges++
        }
    }

    /**
     * Called when the ship jumps away: anything the weapon refers to from
     * the previous fight - its targets, a firing animation in progress -
     * is cancelled, as the ship it was aimed at no longer exists.
     */
    open fun onJump() {
    }

    protected fun damageCloak() {
        // We only damage the cloak if we're fired from the weapons system. If
        // we're fired via the artillery system, from a drone, etc then we
        // don't affect the cloak.
        if (ship.hardpoints.none { it.weapon == this })
            return

        ship.cloaking?.weaponFired()
    }

    protected fun fire() {
        if (ship.isPlayerShip) {
            ship.sys.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.FIRED_SHOT)
            if (type.missilesUsed > 0) ship.sys.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.USED_MISSILE)
        }
        timeCharged = 0f
        extraCharges = 0
        hsFiringAngle = null

        if (xyz.znix.xftl.hs.HS.active) hsShotsFired++

        // Count up
        if (type.boost != null) {
            chainCount = (chainCount + 1).coerceIn(0..type.boost!!.maxCount)
        }

        // Deduct a missile (or multiple), if this weapon uses them
        // This really shouldn't be going negative here, but guard
        // it just in case.
        if (xyz.znix.xftl.hs.HS.active) {
            // ProjectileFactory::Update sets iSpendMissile; a queued launcher spends it after building its volley
            // (WeaponSystem::OnLoop's SpendMissiles), so CONSTRUCT_PROJECTILE sees and may change it.
            hsSpendMissile = if (type.missilesUsed > 0) type.missilesUsed else 0
            if (this !is xyz.znix.xftl.weapons.HsProjectileQueue) hsSpendMissiles()
        } else if (!ship.sys.debugFlags.infiniteMissiles.set && type.missilesUsed > 0) {
            // Explosive Replicator: each volley has a chance to consume
            // no ammo (vanilla: "50 percent chance of not using a missile",
            // rolled once per volley - multi-shot weapons pay once too).
            val replicator = ship.getAugmentValue(AugmentBlueprint.EXPLOSIVE_REPLICATOR)
            if (replicator <= ftl.browser.BrowserRandom.nextFloat()) {
                ship.missilesCount = max(0, ship.missilesCount - type.missilesUsed)
            } else {
                // Vanilla-verified: a proc flashes a '0' by the missile
                // counter, where a consumed volley flashes '-N'.
                ship.replicatorProcPopups++
            }
        }
    }

    /**
     * Hyperspace WeaponAnimation::fDelayChargeTime (+0x250): the part of the charge before the launcher starts to
     * move. WeaponAnimation::SetChargedLevel (0x4608b0) shows frame (int)(max(0, (charge - delay) / (1 - delay)) *
     * iChargedFrame) below a full charge. Natively 0 unless a script writes it.
     */
    @JvmField var hsDelayChargeTime: Float = 0f

    open fun render(g: Graphics) {
        val delay = hsDelayChargeTime
        val level = if (delay == 0f) chargeProgress else ((chargeProgress - delay) / (1f - delay)).coerceAtLeast(0f)
        val frame = if (chargeProgress < 1f) (animation.chargedFrame * level).toInt() else animation.chargedFrame
        val launcher = animation.spriteAt(ship.sys, frame)
        launcher.draw(0f, 0f)

        renderChainChargeLights()

        // Draw the charging glow, if present
        if (isCurrentCharged)
            return
        val glowPath = animation.chargeImage ?: return
        val glow = ship.sys.getImg(glowPath)

        val filter = Colour(1f, 1f, 1f, chargeProgress)
        glow.draw(0f, 0f, filter)
    }

    fun renderChainChargeLights() {
        // For charge weapons, draw on the indicator lights
        if (boostAnim != null && maxTotalCharges > 1 && totalReadyCharges > 0) {
            // 0 indicates one extra charge, so we have to -1.
            boostAnim.spriteAt(totalReadyCharges - 1).draw()
        }

        // For chain weapons, draw the progress
        if (boostAnim != null && type.boost != null && chainCount > 0) {
            // 0 indicates one shot already fired, so -1.
            boostAnim.spriteAt(chainCount - 1).draw()
        }
    }

    fun asWeaponInstance(): AbstractWeaponInstance = this

    /**
     * Power this weapon on or off.
     *
     * This should rarely be used - you should have a very
     * good reason not to use [Weapons.setWeaponPower], since
     * this doesn't check if the weapons system is ion-locked.
     */
    fun forceSetPowered(newPowerState: Boolean) {
        isPowered = newPowerState
    }

    protected open fun createWeaponBarTooltip(): ITooltipProvider {
        val tr = ship.sys.translator
        // Hyperspace WeaponBox::GenerateTooltip (RedesignedTooltips.cpp): <tooltipOverride> replaces the tooltip.
        // With redesignedWeaponTooltips the stat list replaces it (HsTooltips.weaponBoxTooltip).
        // The hotkey and rearrange lines stay below it after a blank line (oracle r2-beacon 07/07b; the stat list
        // ends trimmed, also after MV's WEAPON_STATBOX additions), so they come after WeaponBox::GenerateTooltip.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsTooltips.weaponBoxTooltip(type, tr)?.let { text ->
            return slotTooltip(text + "\n\n" + tr["hotkey"] + "\n" + tr["rearrange"])
        }
        val sb = StringBuilder()

        // Thus function is very heavily based off vanilla's WeaponBlueprint::GetDescription, whose tooltip
        // form ends every line, the type effects included, with " \n": the space widens a line's measure, so the
        // tooltip box is as wide as the original's where such a line is the widest (English "Possible Effect: ...").

        // The "Type: Laser" line
        var typeKey = when (type) {
            is LaserBlueprint -> if (type.ionDamage > 0) "type_ion" else "type_laser"
            is MissileBlueprint -> if (type.missilesUsed > 0) "type_missile" else "type_crystal"
            is BeamBlueprint -> "type_beam"
            is BombBlueprint -> "type_bomb"
            is FlakBlueprint -> "type_burst"
            else -> "type_unknown" // Mods need to override this - TODO find a clean way to do this
        }
        if (type.chargeLevels != null) {
            typeKey += "_charge"
        }
        if (type.boost != null) {
            typeKey += "_chain"
        }
        val typeText: GameText = type.flavourType ?: GameText.localised(typeKey)
        val effectLines = ArrayList<GameText>()

        if (type.missilesUsed > 0) {
            effectLines.add(GameText.localised("type_missile_effect"))
        }
        if (type.ionDamage > 0) {
            effectLines.add(GameText.localised("type_ion_effect"))
        }
        if (type.chargeLevels != null) {
            effectLines.add(GameText.localised("type_charge_effect"))
        }
        if (type.boost != null) {
            effectLines.add(GameText.localised("type_chain_effect"))
        }

        val typeEffectsString = effectLines.joinToString("") { tr[it] + LINE_END }
        sb.append(tr["type_desc"].replaceArg(tr[typeText]).replaceArg(typeEffectsString, 2) + "\n")

        if (type.boost?.type == AbstractWeaponBlueprint.BoostType.COOLDOWN) {
            sb.append(tr["boost_power_speed"] + LINE_END)
            // Max boost speed isn't shown in the tooltip
        }
        if (type.boost?.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
            // We have to include the initial damage in the max value shown.
            // How does vanilla FTL do this?
            // TODO deduplicate this calculation with the one in InfoPanel
            val baseDamage = max(type.damage, type.ionDamage)
            val maxDamage = type.boost!!.maxCount * type.boost!!.perShot + baseDamage
            sb.append(tr["boost_power_damage"] + LINE_END)
            sb.append(tr["damage_cap"].replaceArg(maxDamage) + LINE_END)
        }

        if (type is LaserBlueprint || type is FlakBlueprint || (type is MissileBlueprint && type.shots != 1)) {
            sb.append(tr["shots"].replaceArg(type.shots) + LINE_END)
        }

        if (type.chargeLevels != null) {
            sb.append(tr["charge"].replaceArg(type.chargeLevels!!) + LINE_END)
        }

        val damageKey = if (type is BeamBlueprint) "damage_room" else "damage_shot"
        sb.append(tr[damageKey].replaceArg(type.damage) + LINE_END)

        if (type.shieldPiercing != 0 && type.shieldPiercing != 5) {
            sb.append(tr["shield_piercing"].replaceArg(type.shieldPiercing) + LINE_END)
        }

        val effects = ArrayList<GameText>()
        if (type.breachChance != 0) {
            effects.add(GameText.localised("chance_of_breach"))
        }
        if (type.fireChance != 0) {
            effects.add(GameText.localised("chance_of_fire"))
        }
        if (type.stunChance != 0) {
            effects.add(GameText.localised("chance_of_stun"))
        }
        if (effects.isNotEmpty()) {
            // chance_of_separator as the text file has it: ", " in English, "、" in Japanese and Chinese.
            val separator = tr["chance_of_separator"]

            val specialEffectsString = effects.joinToString(separator) { tr[it] }
            sb.append(tr["chance_of"].replaceArg(specialEffectsString) + LINE_END)
        }

        if (type.ionDamage != 0) {
            sb.append(tr["ion_damage"].replaceArg(type.ionDamage) + LINE_END)
        }
        if (type.stun != 0) {
            sb.append(tr["stun_damage"].replaceArg(type.stun) + LINE_END)
        }
        // WeaponBlueprint::GetDescription (1.6.14 0x605a80, 1.6.9 0x5e2070, which WeaponBox::GenerateTooltip 0x4f5b80
        // calls): the crew damage line only for a positive value, in health points (x15), as in the store.
        if (type.personnelDamage > 0) {
            sb.append(tr["personnel_damage"].replaceArg(type.personnelDamage * 15) + LINE_END)
        }
        if (type.sysDamage != 0) {
            sb.append(tr["system_damage"].replaceArg(type.sysDamage) + LINE_END)
        }
        if (type.hullBust != 0) {
            sb.append(tr["double_damage"] + LINE_END)
        }

        sb.append("\n")
        sb.append(tr["hotkey"] + "\n")
        sb.append(tr["rearrange"])

        return slotTooltip(sb.toString())
    }

    /** The slot key is filled in when shown, as the weapon can be dragged to another slot. */
    private fun slotTooltip(text: String): ITooltipProvider {
        return object : WeaponButtonTooltip(ship.sys, text) {
            override fun getText(): String {
                val slot = ship.hardpoints.indexOfFirst { it.weapon === this@AbstractWeaponInstance }
                return text.replace("\\1", if (slot < 0) "" else ship.sys.shipUI.armamentTooltipKey(true, slot))
            }
        }
    }

    open fun bindToWeaponsSystem(weapons: Weapons) {
    }

    open fun bindToArtillery(artillery: Artillery) {
    }

    open fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttr(elem, "type", type.name)
        SaveUtil.addAttrBool(elem, "powered", isPowered)
        SaveUtil.addAttrFloat(elem, "chargeTime", timeCharged)
        SaveUtil.addTagInt(elem, "extraCharges", extraCharges, 0)
        SaveUtil.addTagInt(elem, "chainCount", chainCount, 0)
        browserEncounterVolleys?.let { elem.setAttribute("browserEncounterVolleys", it.toString()) }
        if (xyz.znix.xftl.hs.HS.active && hsShotsFired != 0) elem.setAttribute("hsShotsFired", hsShotsFired.toString())

        val expectedSlide = if (isPowered) 1f else 0f
        SaveUtil.addTagFloat(elem, "slideAnimation", slide, expectedSlide)
    }

    open fun loadFromXML(elem: Element, refs: RefLoader) {
        require(type.name == SaveUtil.getAttr(elem, "type"))

        isPowered = SaveUtil.getAttrBool(elem, "powered")
        timeCharged = SaveUtil.getAttrFloat(elem, "chargeTime")
        extraCharges = SaveUtil.getOptionalTagInt(elem, "extraCharges") ?: 0
        chainCount = SaveUtil.getOptionalTagInt(elem, "chainCount") ?: 0
        browserEncounterVolleys = elem.getAttributeValue("browserEncounterVolleys")?.toIntOrNull()?.coerceAtLeast(0)
        hsShotsFired = elem.getAttributeValue("hsShotsFired")?.toIntOrNull() ?: 0

        val expectedSlide = if (isPowered) 1f else 0f
        slide = SaveUtil.getOptionalTagFloat(elem, "slideAnimation") ?: expectedSlide
    }

    open class WeaponButtonTooltip(game: InGameState, private val text: String) : DelayedTooltip(game) {
        override fun getText(): String = text
        override val customMaxWidth: Int get() = 300
    }
}

/**
 * Represents a weapon that can be fired at individual rooms. Includes basically everything but beams.
 */
interface IRoomTargetingWeapon {
    fun fire(targetSource: () -> Room)

    fun fireFromDrone(drone: CombatDrone, target: Room)

    fun asWeaponInstance(): AbstractWeaponInstance
}
