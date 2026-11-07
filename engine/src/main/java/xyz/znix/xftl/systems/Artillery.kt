package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.systems.HsArtillery
import xyz.znix.xftl.hs.systems.HsArtilleryButton
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.BombBlueprint
import kotlin.math.max

class Artillery(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    /** The ship being fought when a script added this system: it isn't this system's target. */
    @JvmField var hsAddedAgainst: xyz.znix.xftl.Ship? = null


    private lateinit var weapon: AbstractWeaponInstance

    /** Hyperspace Lua: ArtillerySystem::projectileFactory. */
    internal val hsWeapon: AbstractWeaponInstance get() = weapon
    /** Hyperspace Lua: ArtillerySystem::projectileFactory written by a script. */
    fun hsSetWeapon(w: AbstractWeaponInstance) { weapon = w }
    val browserWeapon: AbstractWeaponInstance get() = weapon

    // Charging appears to work in vanilla based on preserving your charge
    // progress fraction while changing power. This means that if you charge
    // the bar to half way on one power then finish it on four power, it's
    // going to take the average of 50 and 20 seconds.
    private var chargeProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f..1f)
        }

    override val sortingType: SortingType get() = SortingType.ARTILLERY

    override val title: GameText? get() = weapon.type.title
    override val description: GameText? get() = weapon.type.desc

    private val barImages: List<Image> by onInit { game ->
        (1..4).map { game.getImg("img/systemUI/button_artillery_$it.png") }
    }

    // The blueprint's cooldown is correct for a level-2 artillery system.
    internal val touchCharge: Float get() = chargeProgress
    private val cooldown: Float
        get() = weapon.chargeTime * if (xyz.znix.xftl.hs.HS.luaActive) weapon.hsCooldownMod else 1.5f - powerSupplied * 0.25f

    /** What the last frame left in the weapon's charge: a different value there is a script's write. */
    private var hsLastCharged = Float.NaN
    val browserSecondsUntilShot: Float get() = if (powerSupplied > 0) cooldown * (1f - chargeProgress) / (1f + ship.getAugmentValue(AugmentBlueprint.AUTOMATED_RELOADERS)) else Float.POSITIVE_INFINITY

    private val hardpoint by lazy {
        // Find out what our index is, in all the artillery systems specified
        // in the ship's XML.
        // Note we can't use the ship's list of artillery systems, in case
        // (in a mod) there's a ship where some artillery systems have to be
        // purchased, in which case the index in the purchased systems might
        // not match that in the XML.
        val allSystemSlots = ship.rooms.flatMap { it.systemSlots }
        val artillerySlots = allSystemSlots.filter { it.system.type == INFO.name }.sortedBy { it.spec.systemIndex }
        val index = artillerySlots.indexOf(configuration)
        require(index != -1) { "Artillery system is not in ship systems list!" }

        // Hardpoints 4 and up (zero-indexed) are used for artillery.
        // If there's more than four regular weapons however, it starts
        // after the last weapon hardpoint.
        // A mod's ship can name more artillery than its layout has mounts (Multiverse's Cealaformer: five with room for
        // four): the extra ones have an empty mount at the layout's origin.
        ship.hardpoints.getOrNull(max(4, ship.weaponSlots ?: 4) + index)?.spec ?: ship.type.emptyHardpoint
    }

    /** Diagnostics: the mount this artillery fires from. */
    internal val browserMount get() = hardpoint

    override fun initialise(ship: Ship) {
        super.initialise(ship)

        // ArtillerySystem's GetWeaponBlueprint (0x60a9a0): a blueprint list (MV's LIST_ARTILLERY_GUARD) gives one of
        // its members, `random32() % n`. Vanilla artillery always names a weapon.
        val manager = ship.sys.blueprintManager
        val ref = configuration.spec.weapon?.let { manager.getOrNull(it) }
        val picked = if (ref is BlueprintList && ref.names.isNotEmpty())
            manager.getOrNull(ref.names[ftl.browser.NativeRandom.nextInt(ref.names.size)]) else ref
        val weaponBlueprint = picked as? AbstractWeaponBlueprint ?: manager.inertWeapon
        weapon = weaponBlueprint.buildInstance(ship)
        weapon.bindToArtillery(this)
    }

    override fun update(dt: Float) {
        super.update(dt)

        // Hyperspace OnLoop_HS_ManualTarget: the player's targetable artillery drops an aim at a non-hostile enemy.
        val hsManual = xyz.znix.xftl.hs.HS.active && HsArtillery.manual(ship)
        if (hsManual) HsArtillery.validAim(this, ship)

        val hsLua = xyz.znix.xftl.hs.HS.luaActive
        if (hsLua) {
            if (!hsLastCharged.isNaN() && weapon.timeCharged != hsLastCharged)
                chargeProgress = weapon.timeCharged / weapon.chargeTime
            // ProjectileFactory::SetCooldownModifier with WEAPON_COOLDOWN_MOD; a pre-empt keeps the previous modifier.
            xyz.znix.xftl.hs.weapons.HsWeaponsLua.cooldownMod(weapon, 1.5f - powerSupplied * 0.25f, true)
                ?.let { weapon.hsCooldownMod = it.coerceAtLeast(1e-4f) }
        }

        // ArtillerySystem::OnLoop (0x634f20) runs its weapon through
        // ProjectileFactory::Update with a cooldown of 1.5 - 0.25 * power
        // times the blueprint's: unpowered it drains six charge seconds a
        // second, and a hacking pulse drains one.
        if (powerSupplied <= 0) {
            chargeProgress = (chargeProgress - dt * UNPOWERED_DRAIN / cooldown).coerceAtLeast(0f)

            // Make the beam disappear if we were shooting when we were turned off.
            weapon.forceSetPowered(false)
            weapon.update(dt, dt, false)
            if (hsLua) {
                weapon.timeCharged = weapon.chargeTime * chargeProgress
                hsLastCharged = weapon.timeCharged
            }

            return
        }

        var amount = dt / cooldown
        if (ship.sys.debugFlags.fastWeaponCharge.set)
            amount *= 5
        if (isHackActive)
            chargeProgress = (chargeProgress - amount).coerceAtLeast(0f)
        else if (!ship.opponentCloakActive)
            chargeProgress += amount * (1f + ship.getAugmentValue(AugmentBlueprint.AUTOMATED_RELOADERS))

        if (chargeProgress >= 1f && hsManual) {
            // Fires only at the player's aim (WeaponControl arms it like a weapon); the charge waits for one.
            hsFireAtAim()
        } else if (chargeProgress >= 1f) {
            val targetShip = ship.sys.getEnemyOf(ship)
            // ArtillerySystem::OnLoop (0x634f20): a charged artillery holds its shot while the target is cloaked.
            if (targetShip != null && !targetShip.isCloakActive) {
                fireAt(targetShip)
                chargeProgress = 0f
            }
        }

        weapon.forceSetPowered(true)
        weapon.update(dt, dt, true)

        // Set the weapon's time charged to match our charge progress. This is
        // so the flagship's weapons show their usual charging animation.
        weapon.timeCharged = weapon.chargeTime * chargeProgress
        if (hsLua) hsLastCharged = weapon.timeCharged
    }

    /** Hyperspace artilleryGibMountFix: the layout's gib this artillery's mount sits on (0 for none). */
    internal val hsMountGib: Int get() = hardpoint.gib
    internal val hsHardpoint get() = hardpoint

    /**
     * Hyperspace artilleryGibMountFix (CustomShips.cpp ExplosionAnimation::OnRender): ArtillerySystem's
     * projectileFactory->weaponVisual.OnRender(1.f) inside a gib's matrix, the launcher alone (no charge bar).
     */
    internal fun renderOnGib(g: Graphics) {
        g.pushTransform()
        val anim = weapon.animation
        Weapons.translateHardpoint(g, hardpoint)
        g.translate(-anim.mountPoint.x.f, -anim.mountPoint.y.f)
        weapon.render(g)
        g.popTransform()
    }

    override fun drawBackground(g: Graphics) {
        super.drawBackground(g)
        // A wrecked ship's artillery rides its gib instead when Hyperspace's artilleryGibMountFix is on (Ship.renderDestruction)
        if (ship.hsArtilleryOnGibs()) return

        // Draw the weapon launcher animation
        g.pushTransform()

        val anim = weapon.animation

        g.translate(ship.hsHullShiftX.f, ship.hsHullShiftY.f)
        Weapons.translateHardpoint(g, hardpoint)
        g.translate(-anim.mountPoint.x.f, -anim.mountPoint.y.f)

        weapon.render(g)

        Weapons.drawEnemyChargeBar(ship, weapon, hackedBy?.isPoweredUp == true, isHackActive)

        g.popTransform()
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

        val boxX = x + 22 - 6
        val boxY = y - 53 - 6

        if (xyz.znix.xftl.hs.HS.active) {
            // Hyperspace ArtilleryBarFix (any power with a box image, purple while hacked). The bar box stays put with
            // the targetable artillery's button (oracle systems-render: FED with and without ARTILLERY_ORDER).
            HsArtillery.drawBar(game, g, powerSupplied, chargeProgress, isHackActive, boxX, boxY)
            return
        }

        val imageId = max(0, powerSupplied - 1)

        // Draw the image of the box the charge bar sits in
        barImages[imageId].draw(boxX, boxY)

        val barX = boxX + 9
        val barBaseY = boxY + 59

        val maxHeight = 50 - imageId * 10 // one pixel per second of charging
        val barHeight = (chargeProgress * maxHeight).toInt()

        g.colour = Colour.white
        g.fillRect(barX.f, barBaseY.f - barHeight, 5f, barHeight.f)
    }

    override fun onJump() {
        super.onJump()

        chargeProgress = 0f
        // Hyperspace ArtillerySystem::Jump: ClearAiming.
        if (xyz.znix.xftl.hs.HS.active) HsArtillery.clearAim(this)
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        // Hyperspace ArtilleryBox: the aim button of the targetable artillery (shown only while it is targetable).
        if (xyz.znix.xftl.hs.HS.active && ship.isPlayerShip)
            return listOf(HsArtilleryButton(ship.sys, this, powerPos))
        return super.makeExtraButtons(powerPos)
    }

    /** OnLoop_HS_ManualTarget's charged factory: fire at the player's aim, which autofire keeps. */
    private fun hsFireAtAim() {
        val tgt = HsArtillery.validAim(this, ship) ?: return
        when (val weapon = this.weapon) {
            is BeamBlueprint.BeamInstance -> {
                if (tgt !is SelectedTarget.BeamAim) { HsArtillery.clearAim(this); return }
                weapon.fire(tgt)
            }

            is AbstractProjectileWeaponInstance -> {
                if (tgt !is SelectedTarget.RoomAim) { HsArtillery.clearAim(this); return }
                val anim = weapon.animation
                val firePos = hardpoint.position - anim.mountPoint + anim.firePoint
                weapon.fireFromArtillery(List(max(1, weapon.hsNumShots)) { tgt.room }, firePos)
            }

            is BombBlueprint.BombInstance -> {
                if (tgt !is SelectedTarget.RoomAim) { HsArtillery.clearAim(this); return }
                val anim = weapon.animation
                weapon.fireFromArtillery(List(max(1, weapon.hsNumShots)) { tgt.room }, hardpoint.position - anim.mountPoint + anim.firePoint)
            }

            else -> { HsArtillery.clearAim(this); return }
        }
        chargeProgress = 0f
        if (!HsArtillery.autofire(ship)) HsArtillery.clearAim(this)
    }

    private fun fireAt(targetShip: Ship) {
        if (ship.sys.debugFlags.noEnemyFire.set && !ship.isPlayerShip)
            return

        when (val weapon = this.weapon) {
            is BeamBlueprint.BeamInstance -> {
                val startRoom = targetShip.rooms.random(ftl.browser.BrowserRandom)
                // Hyperspace ProjectileFactory::Fire: an artillery pinpoint beam hits a random slot of the room.
                val aim = if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.weapons.HsWeapons.isPinpoint(weapon.type))
                    xyz.znix.xftl.hs.weapons.HsWeapons.pinpointAim(weapon, startRoom, -1, null)
                else weapon.buildLongestAim(startRoom)

                weapon.fire(aim)
            }

            // This handles flak, missiles, and lasers.
            is AbstractProjectileWeaponInstance -> {
                // The firing position won't not be right for the player ship (because
                // of the weapon rotation) if there's a non-zero weapon position,
                // but it'll be fine for vanilla.
                val anim = weapon.animation
                val firePos = hardpoint.position - anim.mountPoint + anim.firePoint

                weapon.fireFromArtillery(targetShip.rooms, firePos)
            }

            // A mod's artillery can name a bomb (Multiverse's Ancient Dreadnought).
            is BombBlueprint.BombInstance -> {
                val anim = weapon.animation
                weapon.fireFromArtillery(targetShip.rooms, hardpoint.position - anim.mountPoint + anim.firePoint)
            }

            else -> error("Artillery system doesn't support weapon ${weapon.type.name} instance $weapon")
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrFloat(elem, "charge", chargeProgress)

        val weaponElem = Element("weapon")
        weapon.saveToXML(weaponElem, refs)
        // The weapon a list picked (absent for a named weapon, as before).
        if (weapon.type.name != configuration.spec.weapon) weaponElem.setAttribute("listPick", weapon.type.name)
        elem.addContent(weaponElem)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        chargeProgress = SaveUtil.getAttrFloat(elem, "charge")

        val weaponElem = elem.getChild("weapon")
        weaponElem.getAttributeValue("listPick")?.let { name ->
            val saved = ship.sys.blueprintManager.getOrNull(name) as? AbstractWeaponBlueprint
            if (weapon.type.name != name && saved != null) {
                weapon = saved.buildInstance(ship)
                weapon.bindToArtillery(this)
            }
        }
        weapon.loadFromXML(weaponElem, refs)
        // ArtillerySystem's ProjectileFactory::LoadState (1.6.9 0x40eab0) restores the queued volley; its shots leave from
        // the artillery's mount (fireAt), which a burst saved between two shots needs again.
        (weapon as? AbstractProjectileWeaponInstance)?.let { w ->
            val anim = w.animation
            w.restoreArtilleryOrigin(hardpoint.position - anim.mountPoint + anim.firePoint)
        }
    }

    companion object {
        private const val UNPOWERED_DRAIN = 6f

        val INFO: SystemInfo = ArtilleryInfo
    }
}

private object ArtilleryInfo : SystemInfo("artillery") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Artillery(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["artillery_1"]
            1 -> translator["artillery_2"]
            2 -> translator["artillery_3"]
            3 -> translator["artillery_4"]
            4 -> translator["artillery_5"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}
