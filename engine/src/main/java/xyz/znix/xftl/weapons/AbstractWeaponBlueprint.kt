package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.game.FTLSound
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InGameState.GameContent
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.RefLoader
import java.util.*

abstract class AbstractWeaponBlueprint(xml: Element) : Blueprint(xml) {
    // Native WeaponBlueprint ctor 1.6.9 72de30 /1.6.14 763dd0: raw RGB227,39,39, alpha1.
    // ProcessWeaponBlueprint5e41d0 only reads integer r/g/b children; absent components under <color> are zero.
    private val colorNode = xml.getChild("color")
    @JvmField val nativeColor = xyz.znix.xftl.hs.lua.GlColorValue(
        if (colorNode == null) 227f else colorNode.nativeInt("r")?.toFloat() ?: 0f,
        if (colorNode == null) 39f else colorNode.nativeInt("g")?.toFloat() ?: 0f,
        if (colorNode == null) 39f else colorNode.nativeInt("b")?.toFloat() ?: 0f, 1f)
    /** Hyperspace's per-weapon data (CustomWeaponDefinition); null unless Hyperspace is active. */
    @JvmField val hs: xyz.znix.xftl.hs.weapons.HsWeaponDef? =
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsWeaponDef.parse(xml) else null

    /** XML fields only Hyperspace's Lua WeaponBlueprint members read. */
    @JvmField val hsLuaFacts = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.lua.LuaAdaptersArmament.Facts(xml) else null
    // Numbers are read as ProcessWeaponBlueprint reads them (NativeNumbers: atoi / strtod); a child that is not
    // there keeps the WeaponBlueprint constructor's value (weapon art "default", the basic laser; radius 0).
    var launcher: String = xml.getChildTextTrim("weaponArt") ?: "default"
    var projectile: String? = xml.getChildTextTrim("image")
    open val explosion: String? = xml.getChildTextTrim("explosion")
    /** Numbers below that Hyperspace scripts write are vars (hyperspace.i leaves them writable); weapons copy shots and power when built. */
    var shots = xml.nativeInt("shots") ?: 1
    val damage = xml.nativeInt("damage") ?: 0
    val sysDamage = xml.nativeInt("sysDamage") ?: 0
    val ionDamage = xml.nativeInt("ion") ?: 0
    val shieldPiercing: Int = xml.nativeInt("sp") ?: 0
    var missilesUsed: Int = xml.nativeInt("missiles") ?: 0
    var speed: Int? = xml.nativeInt("speed")
    /**
     * WeaponBlueprint::radius (`<radius>`, any weapon type; 0 when absent): a burst's spread and the aiming disc of
     * weapons made from it. A var: Hyperspace scripts may write it.
     */
    var blueprintRadius: Int = xml.nativeInt("radius") ?: 0
    /** WeaponBlueprint::spin: a burst factory passes it to LaserBlast::SetSpin for its pieces. */
    var spin: Int = xml.getChildTextTrim("spin")?.toFloatOrNull()?.toInt() ?: 0
    /** `<drone_targetable>`: -1 (absent) keeps the body's Targetable type; 0 makes it 4 (laser), anything else 2. */
    var droneTargetable: Int = xml.getChildTextTrim("drone_targetable")?.toIntOrNull() ?: -1
    val personnelDamage: Int = xml.nativeInt("persDamage") ?: 0
    var chargeLevels: Int? = xml.nativeInt("chargeLevels")
    val hullBust: Int = xml.nativeInt("hullBust") ?: 0

    // atoi truncates decimals (the Insurrection+ mod has floats for these).
    val fireChance: Int = xml.nativeInt("fireChance") ?: 0
    val breachChance: Int = xml.nativeInt("breachChance") ?: 0

    // If set, always 3s stun duration
    val stunChance: Int = xml.nativeInt("stunChance") ?: 0

    // This is the duration, if set always 100% chance
    val stun: Int = xml.nativeInt("stun") ?: 0
    // Hyperspace: a `<customLockdown>` also makes the weapon lock rooms down (CustomWeapons.cpp sets Damage::bLockdown)
    val browserLockdown: Boolean = (xml.nativeInt("lockdown") ?: 0) != 0 || hs?.damage?.lockdownDef != null

    // These tags are from Hyperspace, and disable the hull damage being added
    // to personnel and system damage. Vanilla FTL ignores them, so they only apply while Hyperspace is active.
    val noPersonnelDamage = hs?.damage?.noPersDamage ?: false
    val noSysDamage = hs?.damage?.noSysDamage ?: false

    // Hyperspace tag, a flat reduction to the evasion used in calculations with that gun (can be negative)
    val accuracyModifier: Int = hs?.damage?.accuracyMod ?: 0

    // Power, charge time and cost are null for drone blueprints.
    // Use some semi-sane defaults to avoid having to check everywhere.
    var power = xml.nativeInt("power") ?: 1
    /** WeaponBlueprint::damage once a script has touched it: every hit built from this blueprint reads it. */
    @JvmField var hsLuaDamage: Damage? = null
    /** WeaponBlueprint::cooldown; var: Hyperspace scripts write it (Lua `blueprint.cooldown`). */
    var chargeTime: Float = xml.nativeFloat("cooldown") ?: 5f
    private val xmlCost: Int = xml.nativeInt("cost") ?: 0
    override val cost: Int get() = hsCost ?: xmlCost

    var launchSounds = xml.getChild("launchSounds")?.let { SoundList(it) }
    var hitShipSounds = xml.getChild("hitShipSounds")?.let { SoundList(it) }
    var hitShieldSounds = xml.getChild("hitShieldSounds")?.let { SoundList(it) }
    var missSounds = xml.getChild("missSounds")?.let { SoundList(it) }

    var flavourType: GameText? = xml.getGameTextChild("flavorType")

    var boost: ChainBoost?
    /** `<boost>`'s amount and count as read, kept even when its type is none (WeaponBlueprint::boostPower). */
    var boostAmount: Float
    var boostCount: Int

    /** WeaponBlueprint::explosion written by a script. */
    @JvmField var hsExplosion: String? = null

    init {
        val boostElem = xml.getChild("boost")
        // <boost>: type "cooldown" or "damage" (any other type is no boost), count by atoi, amount by strtod
        val type = when (boostElem?.getChildTextTrim("type")) { "cooldown" -> BoostType.COOLDOWN; "damage" -> BoostType.DAMAGE; else -> null }
        boostAmount = boostElem?.nativeFloat("amount") ?: 0f
        boostCount = boostElem?.nativeInt("count") ?: 0
        boost = if (type == null) null else ChainBoost(type, boostAmount, boostCount)
    }

    fun getLauncher(game: InGameState): Animations.WeaponAnimationSpec {
        game.animations.weaponAnimations[launcher]?.let { return it }

        // Missing animation?
        val sheet = Animations.SpriteSheetSpec(Constants.MISSING_FILE_PATH, 20, 20, 20, 20)
        return Animations.WeaponAnimationSpec(
            sheet, 0, 0, 1, 0, 0,
            ConstPoint.ZERO, ConstPoint.ZERO, null, 0, null
        )
    }

    /**
     * Draw the weapon's launcher image as it should be shown in UIs, for example
     * quest rewards or in a store.
     */
    fun drawLauncherUI(game: InGameState, g: Graphics, x: Float, y: Float) {
        val anim = getLauncher(game)
        val spr = anim.getChargedImage(game)
        drawLauncherUI(spr, g, x, y, game)
    }

    /**
     * [game] lets Hyperspace's `<iconReplace>`/`<iconScale>` apply (WeaponBlueprint::RenderIcon hooks); they
     * draw around the centre of the box the launcher would fill.
     */
    @JvmOverloads
    fun drawLauncherUI(chargedImage: Image, g: Graphics, x: Float, y: Float, game: InGameState? = null) {
        val hs = hs
        if (hs != null && game != null && (hs.iconReplace.isNotEmpty() || hs.iconScale != 1f)) {
            xyz.znix.xftl.hs.weapons.HsWeapons.drawIcon(this, hs, game, chargedImage, g, x + chargedImage.height / 2f, y + chargedImage.width / 2f)
            return
        }
        drawLauncherRotated(chargedImage, g, x, y)
    }

    /** The launcher sprite turned to lie on a horizontal surface, its box's top left at [x], [y]. */
    fun drawLauncherRotated(chargedImage: Image, g: Graphics, x: Float, y: Float) {
        g.pushTransform()

        // Flip and rotate the sprite appropriately to make it loop like it's mounted above
        // a horizontal surface.

        // Note we have to add the width (height, but we've rotated it 90°) and height
        // to fix the offset caused by the rotation and mirroring.
        g.translate(x + chargedImage.height, y + chargedImage.width)

        g.scale(1f, -1f)
        g.rotate(0f, 0f, 90f)

        chargedImage.draw(0, 0)

        g.popTransform()
    }

    override fun finishSetup(content: GameContent) {
        super.finishSetup(content)

        // Load all the sounds
        launchSounds?.load(content)
        hitShipSounds?.load(content)
        hitShieldSounds?.load(content)
        missSounds?.load(content)
    }

    abstract fun buildInstance(ship: Ship): AbstractWeaponInstance

    open fun loadProjectileFromXML(
        game: InGameState,
        elem: Element, refs: RefLoader,
        callback: ProjectileLoadCallback
    ) {
        throw UnsupportedOperationException("Weapon blueprint '$name' doesn't support weapon projectile deserialisation.")
    }

    class SoundList(elem: Element) {
        /** Hyperspace: the list a script wrote; [hsContent] is the game it was loaded for. */
        constructor(names: List<String>, content: GameContent) : this(Element("sounds").also { e -> names.forEach { e.addContent(Element("sound").setText(it)) } }) { load(content) }

        private val names: List<String> = elem.getChildren("sound").map { it.textTrim }
        private var soundsInternal: List<FTLSound?>? = null

        /**
         * The sounds in this list.
         *
         * Null entries represent invalid sounds, which don't appear in sounds.xml.
         */
        val sounds: List<FTLSound?> get() = soundsInternal ?: error("SoundList not yet loaded!")

        fun load(content: GameContent) {
            if (soundsInternal != null) {
                error("Cannot re-initialise sound list!")
            }

            soundsInternal = names.map { content.sounds.getSampleOrWarn(it) }
        }

        /** One sound name, as Projectile::Initialize stores it in hitSolidSound/hitShieldSound/missSound. */
        /** 1.6.14 0x602cb0: random32() % size (no draw for an empty list, which gives ""). */
        fun randomName(): String = if (names.isEmpty()) "" else names[ftl.browser.BrowserRandom.nextBits(31) % names.size]

        fun get(): FTLSound? {
            if (sounds.isEmpty())
                return null
            return sounds.random(ftl.browser.BrowserRandom)
        }
    }

    // This is for the charge lasers (including the Vulkan) and the chain ion
    class ChainBoost(val type: BoostType, val perShot: Float, val maxCount: Int)
    enum class BoostType { COOLDOWN, DAMAGE }
}
