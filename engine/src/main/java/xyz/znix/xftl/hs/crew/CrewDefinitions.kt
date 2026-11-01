// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.h, CustomCrewCommon.h, CustomLockdowns.h,
// ToggleValue.h
package xyz.znix.xftl.hs.crew

import org.jdom2.Element
import xyz.znix.xftl.hs.stat.StatBoostDefinition

/** HS `ToggleValue<T>`: a temporary-effect override that only applies when [enabled]. */
class Toggle<T>(@JvmField var value: T) {
    @JvmField var enabled = false
    /** Runs after a script's write (a toggle over an engine field). */
    @JvmField var onWrite: (() -> Unit)? = null
    fun set(v: T) { value = v; enabled = true }
    fun copy(): Toggle<T> = Toggle(value).also { it.enabled = enabled }
}

/** GL_Color as HS keeps it (0..1 floats). */
data class HsColour(var r: Float, var g: Float, var b: Float, var a: Float)

/** Text that is either literal or a text id (HS `TextString`). */
data class HsText(var data: String = "", var isLiteral: Boolean = false)

/** The fields of FTL's `Damage` HS uses for crew powers and death effects. */
data class HsDamage(
    var iDamage: Int = 0,
    var iShieldPiercing: Int = 0,
    var fireChance: Int = 0,
    var breachChance: Int = 0,
    var stunChance: Int = 0,
    var iIonDamage: Int = 0,
    var iSystemDamage: Int = 0,
    var iPersDamage: Int = 0,
    var bHullBuster: Boolean = false,
    var ownerId: Int = -1,
    var selfId: Int = -1,
    var bLockdown: Boolean = false,
    var crystalShard: Boolean = false,
    var bFriendlyFire: Boolean = false,
    var iStun: Int = 0,
)

/** CustomLockdownDefinition; defaults are vanilla's lockdown (12 s, 50 hp, crystal_1/crystal_2, dilatable). */
data class LockdownDefinition(
    var duration: Float = 12f,
    var health: Int = 50,
    var colour: HsColour = HsColour(1f, 1f, 1f, 1f),
    var anims: List<String> = listOf("crystal_1", "crystal_2"),
    var canDilate: Boolean = true,
)

class CrewSpawn {
    @JvmField var idx = -1
    @JvmField var race = ""
    @JvmField var name = ""
    @JvmField var healthPercentage = 1f
    @JvmField var lifetime = -1f
    @JvmField val statBoosts = ArrayList<StatBoostDefinition>()
    @JvmField var noSlot = false
    @JvmField var enemy = false
    @JvmField var number = 1
}

/** ExplosionDefinition (death effects). Copied by value where HS copies it. */
class ExplosionDefinition {
    @JvmField var damage = HsDamage()
    @JvmField var shipFriendlyFire = false
    @JvmField var statBoosts = ArrayList<StatBoostDefinition>()
    @JvmField var roomStatBoosts = ArrayList<StatBoostDefinition>()
    @JvmField var crewSpawns = ArrayList<CrewSpawn>()
    @JvmField var transformRace = ""
    @JvmField var transformRaceHealth = 0f
    @JvmField var transformRaceHealthFraction = 1f
    @JvmField var transformRaceDeathSound = false
    /** Event names for player (0) and enemy (1) crew; the events workstream registers the inline events. */
    @JvmField val event = arrayOf("", "")
    @JvmField var eventNodes = arrayOfNulls<Element>(2)
    @JvmField var customLockdown = xyz.znix.xftl.hs.ship.HsLockdowns.fresh()

    fun copy(): ExplosionDefinition = ExplosionDefinition().also {
        it.damage = damage.copy(); it.shipFriendlyFire = shipFriendlyFire
        it.statBoosts = ArrayList(statBoosts); it.roomStatBoosts = ArrayList(roomStatBoosts); it.crewSpawns = ArrayList(crewSpawns)
        it.transformRace = transformRace; it.transformRaceHealth = transformRaceHealth
        it.transformRaceHealthFraction = transformRaceHealthFraction; it.transformRaceDeathSound = transformRaceDeathSound
        it.event[0] = event[0]; it.event[1] = event[1]; it.eventNodes = eventNodes.copyOf()
        it.customLockdown = customLockdown.copy()
    }
}

enum class CrewExtraCondition(val xmlName: String) {
    BURNING("burning"), SUFFOCATING("suffocating"), MIND_CONTROLLED("mindControlled"), STUNNED("stunned"),
    REPAIRING("repairing"), REPAIRING_SYSTEM("repairingSystem"), REPAIRING_BREACH("repairingBreach"),
    FIGHTING("fighting"), SABOTAGING("sabotaging"), SHOOTING("shooting"), MOVING("moving"), IDLE("idle"),
    MANNING("manning"), FIREFIGHTING("firefighting"), DYING("dying"), TELEPORTING("teleporting");
}

class ActivatedPowerRequirements(@JvmField var type: Type = Type.UNKNOWN) {
    enum class Type { PLAYER, ENEMY, CHARGE, UNKNOWN }

    @JvmField var playerShip = false
    @JvmField var enemyShip = false
    @JvmField var checkRoomCrew = false
    @JvmField var enemyInRoom = false
    @JvmField var friendlyInRoom = false
    @JvmField var whiteList = ArrayList<String>()
    @JvmField var friendlyWhiteList = ArrayList<String>()
    @JvmField var friendlyBlackList = ArrayList<String>()
    @JvmField var enemyWhiteList = ArrayList<String>()
    @JvmField var enemyBlackList = ArrayList<String>()
    @JvmField var systemInRoom = false
    @JvmField var systemDamaged = false
    @JvmField var hasClonebay = false
    @JvmField var aiDisabled = false
    @JvmField var outOfCombat = false
    @JvmField var inCombat = false
    @JvmField var requiredSystem = -1
    @JvmField var requiredSystemFunctional = false
    @JvmField var minHealth = Toggle(0)
    @JvmField var maxHealth = Toggle(0)
    @JvmField var extraConditions = ArrayList<Pair<CrewExtraCondition, Boolean>>()
    @JvmField var extraOrConditions = ArrayList<Pair<CrewExtraCondition, Boolean>>()
    @JvmField var extraOrConditionsTooltip = HsText()

    fun copy(): ActivatedPowerRequirements = ActivatedPowerRequirements(type).also {
        it.playerShip = playerShip; it.enemyShip = enemyShip; it.checkRoomCrew = checkRoomCrew
        it.enemyInRoom = enemyInRoom; it.friendlyInRoom = friendlyInRoom
        it.whiteList = ArrayList(whiteList); it.friendlyWhiteList = ArrayList(friendlyWhiteList)
        it.friendlyBlackList = ArrayList(friendlyBlackList); it.enemyWhiteList = ArrayList(enemyWhiteList)
        it.enemyBlackList = ArrayList(enemyBlackList); it.systemInRoom = systemInRoom; it.systemDamaged = systemDamaged
        it.hasClonebay = hasClonebay; it.aiDisabled = aiDisabled; it.outOfCombat = outOfCombat; it.inCombat = inCombat
        it.requiredSystem = requiredSystem; it.requiredSystemFunctional = requiredSystemFunctional
        it.minHealth = minHealth.copy(); it.maxHealth = maxHealth.copy()
        it.extraConditions = ArrayList(extraConditions); it.extraOrConditions = ArrayList(extraOrConditions)
        it.extraOrConditionsTooltip = extraOrConditionsTooltip.copy()
    }
}

/** TemporaryPowerDefinition: the `<temporaryEffect>` of a power. */
class TemporaryPowerDefinition {
    @JvmField var duration = 0f
    @JvmField var effectAnim = ""
    @JvmField var effectFinishAnim = ""
    @JvmField var animSheet = ""
    @JvmField var baseVisible = true
    @JvmField var soundsEnemy = true
    @JvmField var sounds = ArrayList<String>()

    @JvmField var maxHealth = Toggle(0)
    @JvmField var stunMultiplier = Toggle(0f)
    @JvmField var moveSpeedMultiplier = Toggle(0f)
    @JvmField var damageMultiplier = Toggle(0f)
    @JvmField var rangedDamageMultiplier = Toggle(0f)
    @JvmField var doorDamageMultiplier = Toggle(0f)
    @JvmField var repairSpeed = Toggle(0f)
    @JvmField var fireRepairMultiplier = Toggle(0f)
    @JvmField var controllable = Toggle(false)
    @JvmField var canFight = Toggle(false)
    @JvmField var canRepair = Toggle(false)
    @JvmField var canSabotage = Toggle(false)
    @JvmField var canMan = Toggle(false)
    @JvmField var canTeleport = Toggle(false)
    @JvmField var canSuffocate = Toggle(false)
    @JvmField var canBurn = Toggle(false)
    @JvmField var oxygenChangeSpeed = Toggle(0f)
    @JvmField var canPhaseThroughDoors = Toggle(false)
    @JvmField var fireDamageMultiplier = Toggle(0f)
    @JvmField var persDamageMultiplier = Toggle(0f)
    @JvmField var persHealMultiplier = Toggle(0f)
    @JvmField var isTelepathic = Toggle(false)
    @JvmField var resistsMindControl = Toggle(false)
    @JvmField var isAnaerobic = Toggle(false)
    @JvmField var detectsLifeforms = Toggle(false)
    @JvmField var damageTakenMultiplier = Toggle(0f)
    @JvmField var cloneSpeedMultiplier = Toggle(0f)
    @JvmField var passiveHealAmount = Toggle(0f)
    @JvmField var truePassiveHealAmount = Toggle(0f)
    @JvmField var healAmount = Toggle(0f)
    @JvmField var trueHealAmount = Toggle(0f)
    @JvmField var passiveHealDelay = Toggle(0)
    @JvmField var sabotageSpeedMultiplier = Toggle(0f)
    @JvmField var allDamageTakenMultiplier = Toggle(0f)
    @JvmField var healSpeed = Toggle(0f)
    @JvmField var suffocationModifier = Toggle(0f)
    @JvmField var healCrewAmount = Toggle(0f)
    @JvmField var powerDrain = Toggle(0)
    @JvmField var powerDrainFriendly = Toggle(false)
    @JvmField var bonusPower = Toggle(0)
    @JvmField var damageEnemiesAmount = Toggle(0f)
    @JvmField var hackDoors = Toggle(false)
    @JvmField var powerRechargeMultiplier = Toggle(0f)
    @JvmField var noClone = Toggle(false)
    @JvmField var noAI = Toggle(false)
    @JvmField var validTarget = Toggle(false)
    @JvmField var canMove = Toggle(false)
    @JvmField var teleportMove = Toggle(false)
    @JvmField var teleportMoveOtherShip = Toggle(false)
    @JvmField var silenced = Toggle(false)
    @JvmField var lowHealthThreshold = Toggle(0f)
    @JvmField var noWarning = Toggle(false)

    @JvmField var statBoosts = ArrayList<StatBoostDefinition>()
    @JvmField var invulnerable = false
    @JvmField var animFrame = -1
    @JvmField var cooldownColour = HsColour(1f, 1f, 1f, 1f)

    fun copy(): TemporaryPowerDefinition {
        val c = TemporaryPowerDefinition()
        c.duration = duration; c.effectAnim = effectAnim; c.effectFinishAnim = effectFinishAnim; c.animSheet = animSheet
        c.baseVisible = baseVisible; c.soundsEnemy = soundsEnemy; c.sounds = ArrayList(sounds)
        c.maxHealth = maxHealth.copy(); c.stunMultiplier = stunMultiplier.copy(); c.moveSpeedMultiplier = moveSpeedMultiplier.copy()
        c.damageMultiplier = damageMultiplier.copy(); c.rangedDamageMultiplier = rangedDamageMultiplier.copy()
        c.doorDamageMultiplier = doorDamageMultiplier.copy(); c.repairSpeed = repairSpeed.copy()
        c.fireRepairMultiplier = fireRepairMultiplier.copy(); c.controllable = controllable.copy(); c.canFight = canFight.copy()
        c.canRepair = canRepair.copy(); c.canSabotage = canSabotage.copy(); c.canMan = canMan.copy()
        c.canTeleport = canTeleport.copy(); c.canSuffocate = canSuffocate.copy(); c.canBurn = canBurn.copy()
        c.oxygenChangeSpeed = oxygenChangeSpeed.copy(); c.canPhaseThroughDoors = canPhaseThroughDoors.copy()
        c.fireDamageMultiplier = fireDamageMultiplier.copy(); c.persDamageMultiplier = persDamageMultiplier.copy()
        c.persHealMultiplier = persHealMultiplier.copy(); c.isTelepathic = isTelepathic.copy()
        c.resistsMindControl = resistsMindControl.copy(); c.isAnaerobic = isAnaerobic.copy()
        c.detectsLifeforms = detectsLifeforms.copy(); c.damageTakenMultiplier = damageTakenMultiplier.copy()
        c.cloneSpeedMultiplier = cloneSpeedMultiplier.copy(); c.passiveHealAmount = passiveHealAmount.copy()
        c.truePassiveHealAmount = truePassiveHealAmount.copy(); c.healAmount = healAmount.copy()
        c.trueHealAmount = trueHealAmount.copy(); c.passiveHealDelay = passiveHealDelay.copy()
        c.sabotageSpeedMultiplier = sabotageSpeedMultiplier.copy(); c.allDamageTakenMultiplier = allDamageTakenMultiplier.copy()
        c.healSpeed = healSpeed.copy(); c.suffocationModifier = suffocationModifier.copy(); c.healCrewAmount = healCrewAmount.copy()
        c.powerDrain = powerDrain.copy(); c.powerDrainFriendly = powerDrainFriendly.copy(); c.bonusPower = bonusPower.copy()
        c.damageEnemiesAmount = damageEnemiesAmount.copy(); c.hackDoors = hackDoors.copy()
        c.powerRechargeMultiplier = powerRechargeMultiplier.copy(); c.noClone = noClone.copy(); c.noAI = noAI.copy()
        c.validTarget = validTarget.copy(); c.canMove = canMove.copy(); c.teleportMove = teleportMove.copy()
        c.teleportMoveOtherShip = teleportMoveOtherShip.copy(); c.silenced = silenced.copy()
        c.lowHealthThreshold = lowHealthThreshold.copy(); c.noWarning = noWarning.copy()
        c.statBoosts = ArrayList(statBoosts); c.invulnerable = invulnerable; c.animFrame = animFrame
        c.cooldownColour = cooldownColour.copy()
        return c
    }
}

/** Cooldown/charge behaviour constants shared by powers and power resources (HS enums). */
object PowerConst {
    const val JUMP_COOLDOWN_FULL = 0
    const val JUMP_COOLDOWN_RESET = 1
    const val JUMP_COOLDOWN_CONTINUE = 2
    const val DISABLED_COOLDOWN_FULL = 0
    const val DISABLED_COOLDOWN_RESET = 1
    const val DISABLED_COOLDOWN_CONTINUE = 2
    const val DISABLED_COOLDOWN_PAUSE = 3
    const val DISABLED_COOLDOWN_ZERO = 4
    const val ON_DEATH_CONTINUE = 0
    const val ON_DEATH_CANCEL = 1
    const val ON_DEATH_RESET = 2
    const val HOTKEY_FIRST = 0
    const val HOTKEY_ALWAYS = 1
    const val HOTKEY_NEVER = 2
}

class ActivatedPowerDefinition {
    @JvmField var index = 0
    @JvmField var name = ""
    @JvmField var activateGroupIndex = 0
    @JvmField var replaceGroupIndex = 0
    @JvmField var sortOrder = 0

    @JvmField var damage = HsDamage(ownerId = -1, selfId = -1)
    @JvmField var customLockdown = xyz.znix.xftl.hs.ship.HsLockdowns.fresh()
    @JvmField var cooldown = 50f
    @JvmField var shipFriendlyFire = false
    @JvmField var hasSpecialPower = false
    @JvmField var hasTemporaryPower = false
    @JvmField var jumpCooldown = PowerConst.JUMP_COOLDOWN_FULL
    @JvmField var disabledCooldown = PowerConst.DISABLED_COOLDOWN_PAUSE
    @JvmField var initialCooldownFraction = 0f
    @JvmField var onDeath = PowerConst.ON_DEATH_RESET
    @JvmField var onHotkey = PowerConst.HOTKEY_FIRST

    @JvmField var powerCharges = -1
    @JvmField var initialCharges = Int.MAX_VALUE
    @JvmField var chargesPerJump = 1073741823
    @JvmField var respawnCharges = 0
    @JvmField var disabledCharges = PowerConst.DISABLED_COOLDOWN_PAUSE

    @JvmField var hideCooldown = false
    @JvmField var hideCharges = false
    @JvmField var hideButton = false

    @JvmField var powerResources = ArrayList<PowerResourceDefinition?>()

    @JvmField var sounds = ArrayList<String>()
    @JvmField var effectSounds = ArrayList<String>()
    @JvmField var soundsEnemy = true
    @JvmField var effectSoundsEnemy = true

    @JvmField var buttonLabel = HsText()
    @JvmField var cooldownColour = HsColour(133f / 255f, 231f / 255f, 237f / 255f, 1f)

    @JvmField var tooltip = HsText()
    @JvmField var effectAnim = ""
    @JvmField var effectPostAnim = ""

    @JvmField var playerReq = ActivatedPowerRequirements(ActivatedPowerRequirements.Type.PLAYER)
    @JvmField var enemyReq = ActivatedPowerRequirements(ActivatedPowerRequirements.Type.ENEMY)
    @JvmField var chargeReq: ActivatedPowerRequirements? = null

    @JvmField var win = false
    @JvmField var crewHealth = 0f
    @JvmField var enemyHealth = 0f
    @JvmField var selfHealth = 0f
    @JvmField var animFrame = -1
    @JvmField var followCrew = false

    @JvmField var activateWhenReady = false
    @JvmField var activateReadyEnemies = false
    @JvmField var transformRace = ""

    @JvmField var crewSpawns = ArrayList<CrewSpawn>()
    @JvmField var statBoosts = ArrayList<StatBoostDefinition>()
    @JvmField var roomStatBoosts = ArrayList<StatBoostDefinition>()

    @JvmField val event = arrayOf("", "")
    @JvmField var eventNodes = arrayOfNulls<Element>(2)

    @JvmField var tempPower = TemporaryPowerDefinition()

    /** Assign every field from [o] (HS `*def = *copyDef`). Lists are copied shallowly, like std::vector copies. */
    fun assignFrom(o: ActivatedPowerDefinition) {
        index = o.index; name = o.name; activateGroupIndex = o.activateGroupIndex; replaceGroupIndex = o.replaceGroupIndex
        sortOrder = o.sortOrder; damage = o.damage.copy(); customLockdown = o.customLockdown.copy(); cooldown = o.cooldown
        shipFriendlyFire = o.shipFriendlyFire; hasSpecialPower = o.hasSpecialPower; hasTemporaryPower = o.hasTemporaryPower
        jumpCooldown = o.jumpCooldown; disabledCooldown = o.disabledCooldown; initialCooldownFraction = o.initialCooldownFraction
        onDeath = o.onDeath; onHotkey = o.onHotkey; powerCharges = o.powerCharges; initialCharges = o.initialCharges
        chargesPerJump = o.chargesPerJump; respawnCharges = o.respawnCharges; disabledCharges = o.disabledCharges
        hideCooldown = o.hideCooldown; hideCharges = o.hideCharges; hideButton = o.hideButton
        powerResources = ArrayList(o.powerResources); sounds = ArrayList(o.sounds); effectSounds = ArrayList(o.effectSounds)
        soundsEnemy = o.soundsEnemy; effectSoundsEnemy = o.effectSoundsEnemy; buttonLabel = o.buttonLabel.copy()
        cooldownColour = o.cooldownColour.copy(); tooltip = o.tooltip.copy(); effectAnim = o.effectAnim
        effectPostAnim = o.effectPostAnim; playerReq = o.playerReq.copy(); enemyReq = o.enemyReq.copy()
        chargeReq = o.chargeReq // HS copies the pointer
        win = o.win; crewHealth = o.crewHealth; enemyHealth = o.enemyHealth; selfHealth = o.selfHealth
        animFrame = o.animFrame; followCrew = o.followCrew; activateWhenReady = o.activateWhenReady
        activateReadyEnemies = o.activateReadyEnemies; transformRace = o.transformRace
        crewSpawns = ArrayList(o.crewSpawns); statBoosts = ArrayList(o.statBoosts); roomStatBoosts = ArrayList(o.roomStatBoosts)
        event[0] = o.event[0]; event[1] = o.event[1]; eventNodes = o.eventNodes.copyOf()
        tempPower = o.tempPower.copy()
    }

    override fun toString() = "ActivatedPowerDefinition($index, '$name')"
}

class PowerResourceDefinition {
    @JvmField var index = 0
    @JvmField var name = ""
    @JvmField var groupIndex = 0
    @JvmField var sortOrder = 0
    @JvmField var cooldown = 0f
    @JvmField var jumpCooldown = PowerConst.JUMP_COOLDOWN_FULL
    @JvmField var disabledCooldown = PowerConst.DISABLED_COOLDOWN_PAUSE
    @JvmField var initialCooldownFraction = 0f
    @JvmField var onDeath = PowerConst.ON_DEATH_RESET
    @JvmField var powerCharges = -1
    @JvmField var initialCharges = Int.MAX_VALUE
    @JvmField var chargesPerJump = 1073741823
    @JvmField var respawnCharges = 0
    @JvmField var disabledCharges = PowerConst.DISABLED_COOLDOWN_PAUSE
    @JvmField var hideCooldown = false
    @JvmField var hideCharges = false
    @JvmField var showTemporaryBars = false
    @JvmField var showLinkedCooldowns = false
    @JvmField var showLinkedCharges = false
    @JvmField var cooldownColour = HsColour(133f / 255f, 231f / 255f, 237f / 255f, 1f)
    @JvmField var chargeReq: ActivatedPowerRequirements? = null

    fun assignFrom(o: PowerResourceDefinition) {
        index = o.index; name = o.name; groupIndex = o.groupIndex; sortOrder = o.sortOrder; cooldown = o.cooldown
        jumpCooldown = o.jumpCooldown; disabledCooldown = o.disabledCooldown; initialCooldownFraction = o.initialCooldownFraction
        onDeath = o.onDeath; powerCharges = o.powerCharges; initialCharges = o.initialCharges; chargesPerJump = o.chargesPerJump
        respawnCharges = o.respawnCharges; disabledCharges = o.disabledCharges; hideCooldown = o.hideCooldown
        hideCharges = o.hideCharges; showTemporaryBars = o.showTemporaryBars; showLinkedCooldowns = o.showLinkedCooldowns
        showLinkedCharges = o.showLinkedCharges; cooldownColour = o.cooldownColour.copy(); chargeReq = o.chargeReq
    }
}

/** HS `Skill` / `SkillsDefinition`: the per-race skill requirement (actions per level) and levels. */
class SkillsDefinition {
    class Skill(@JvmField var requirement: Int, @JvmField val levels: FloatArray)

    /** Keyed piloting, engines, shields, weapons, repair, combat. */
    @JvmField val skills = linkedMapOf(
        "piloting" to Skill(15, floatArrayOf(0f, 5f, 7f, 10f)),
        "engines" to Skill(15, floatArrayOf(0f, 5f, 7f, 10f)),
        "shields" to Skill(55, floatArrayOf(1f, 1.1f, 1.25f, 1.3f)),
        "weapons" to Skill(65, floatArrayOf(0f, 0.9f, 0.85f, 0.8f)),
        "repair" to Skill(18, floatArrayOf(1f, 1f, 1.1f, 1.2f)),
        "combat" to Skill(8, floatArrayOf(1f, 1f, 1.1f, 1.2f)),
    )

    companion object {
        @JvmField val ORDER = arrayOf("piloting", "engines", "shields", "weapons", "repair", "combat")
    }
}

class DroneAI {
    @JvmField var hasCustomAI = false
    @JvmField var fightAI = false
    @JvmField var repairAI = false
    @JvmField var manAI = false
    @JvmField var batteryAI = false
    @JvmField var returnToDroneRoom = false
}

/** HS `CrewDefinition`: one `<race>` of hyperspace.xml `<crew>`. Defaults are HS's. */
class CrewDefinition(@JvmField var race: String) {
    @JvmField val deathSounds = ArrayList<String>()
    @JvmField val deathSoundsFemale = ArrayList<String>()
    @JvmField val shootingSounds = ArrayList<String>()
    @JvmField val repairSounds = ArrayList<String>()
    @JvmField var repairSoundFrame = -1

    @JvmField var canFight = true
    @JvmField var canRepair = true
    @JvmField var canSabotage = true
    @JvmField var canMan = true
    @JvmField var canTeleport = true
    @JvmField var canSuffocate = true
    @JvmField var controllable = true
    @JvmField var selectable = false
    @JvmField var canBurn = true
    @JvmField var maxHealth = 100
    @JvmField var stunMultiplier = 1f
    @JvmField var moveSpeedMultiplier = 1f
    @JvmField var repairSpeed = 1f
    @JvmField var damageMultiplier = 1f
    @JvmField var cloneSpeedMultiplier = 1f
    @JvmField var rangedDamageMultiplier = 1f
    @JvmField var doorDamageMultiplier = 1f
    @JvmField var providesPower = false
    @JvmField var bonusPower = 0
    @JvmField var fireRepairMultiplier = 1.2f
    @JvmField var suffocationModifier = 1f
    @JvmField var isTelepathic = false
    @JvmField var resistsMindControl = false
    @JvmField var isAnaerobic = false
    @JvmField var fireDamageMultiplier = 1f
    @JvmField var persDamageMultiplier = 1f
    @JvmField var persHealMultiplier = 1f
    @JvmField var canPhaseThroughDoors = false
    @JvmField var oxygenChangeSpeed = 0f
    @JvmField var damageTakenMultiplier = 1f
    @JvmField var passiveHealAmount = 0f
    @JvmField var truePassiveHealAmount = 0f
    @JvmField var healAmount = 0f
    @JvmField var trueHealAmount = 0f
    @JvmField var passiveHealDelay = 0
    @JvmField var detectsLifeforms = false
    @JvmField var hasCustomDeathAnimation = false
    @JvmField var hasDeathExplosion = false
    @JvmField var animBase = "human"
    /** [0] female (or both), [1] male; empty = the race name. */
    @JvmField val animSheet = arrayOf("", "")
    @JvmField var sabotageSpeedMultiplier = 1f
    @JvmField var allDamageTakenMultiplier = 1f
    @JvmField var defaultSkillLevel = 0
    @JvmField var healSpeed = 1f
    @JvmField var cloneLoseSkills = true
    @JvmField var healCrewAmount = 0f
    @JvmField var droneAI = DroneAI()
    @JvmField var droneMoveFromManningSlot = true
    @JvmField var powerDrain = 0
    @JvmField var powerDrainFriendly = false
    @JvmField var damageEnemiesAmount = 0f
    @JvmField var hackDoors = false
    @JvmField var powerRechargeMultiplier = 1f
    @JvmField var crewSlots = 1f
    @JvmField var noSlot = false
    @JvmField var noClone = false
    @JvmField var noAI = false
    @JvmField var validTarget = true
    @JvmField var canPunch = Toggle(true)
    @JvmField var canMove = true
    @JvmField var snapToSlot = false
    @JvmField var teleportMove = false
    @JvmField var teleportMoveOtherShip = false
    @JvmField var essential = 0f
    @JvmField var silenced = false
    @JvmField var lowHealthThreshold = 25f
    @JvmField var lowHealthThresholdPercentage = 1f
    @JvmField var noWarning = false

    @JvmField var shootTimer = intArrayOf(-1, -1)
    @JvmField var punchTimer = intArrayOf(-1, -1)

    @JvmField var explosionDef = ExplosionDefinition()

    @JvmField val powerDefs = ArrayList<ActivatedPowerDefinition?>()
    @JvmField val passiveStatBoosts = ArrayList<StatBoostDefinition>()

    @JvmField val nameRace = ArrayList<String>()
    @JvmField val transformName = ArrayList<String>()
    @JvmField var changeIfSame = true

    @JvmField var skillsDef = SkillsDefinition()

    /** Set when HS's `blueprintNames[name]` created this entry on a lookup (not from XML). */
    @JvmField var implicit = false

    override fun toString() = "CrewDefinition($race)"
}
