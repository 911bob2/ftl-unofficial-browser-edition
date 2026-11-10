// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CrewAbilities.cpp (ActivatedPower,
// ActivatedPowerResource, CalculatePowerDef, UpdateAbilityStatBoosts), src/game/Ship/CrewMember_Extend.h,
// src/game/Ship/CrewVTable.cpp (ResetPower, ActivatePower), src/game/Ship/CustomCrew.cpp (CrewMember::Restart)
package xyz.znix.xftl.hs.crew

import org.jdom2.Element
import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.StatBoost
import xyz.znix.xftl.hs.stat.StatBoostManager

/** HS `PowerReadyState`. */
object PowerReadyState {
    const val NOT_READY_COOLDOWN = 0
    const val READY = 1
    const val NOT_READY_ACTIVATED = 2
    const val NOT_READY_ENEMY_SHIP = 3
    const val NOT_READY_PLAYER_SHIP = 4
    const val NOT_READY_ENEMY_IN_ROOM = 5
    const val NOT_READY_FRIENDLY_IN_ROOM = 6
    const val NOT_READY_WHITELIST = 7
    const val NOT_READY_ENEMY_WHITELIST = 8
    const val NOT_READY_FRIENDLY_WHITELIST = 9
    const val NOT_READY_ENEMY_BLACKLIST = 10
    const val NOT_READY_FRIENDLY_BLACKLIST = 11
    const val NOT_READY_SYSTEM_IN_ROOM = 12
    const val NOT_READY_HAS_CLONEBAY = 13
    const val NOT_READY_AI_DISABLED = 14
    const val NOT_READY_OUT_OF_COMBAT = 15
    const val NOT_READY_IN_COMBAT = 16
    const val NOT_READY_SYSTEM = 17
    const val NOT_READY_SYSTEM_FUNCTIONAL = 18
    const val NOT_READY_MIN_HEALTH = 19
    const val NOT_READY_MAX_HEALTH = 20
    const val NOT_READY_SYSTEM_DAMAGED = 21
    const val NOT_READY_CHARGES = 22
    const val NOT_READY_SILENCED = 23
    const val NOT_READY_EXTRACONDITION_OR = 24
    const val NOT_READY_EXTRACONDITION_TRUE = 16384
    const val NOT_READY_EXTRACONDITION_FALSE = 32768
    const val NOT_READY_CUSTOM = 65536
}

/** The (current, goal) pairs HS keeps as std::pair. */
class FloatPair(@JvmField var first: Float = 0f, @JvmField var second: Float = 0f)
class IntPair(@JvmField var first: Int = 0, @JvmField var second: Int = 0)

/** Shared by [ActivatedPower] and [ActivatedPowerResource] (HS's PowerReqStatic template). */
abstract class PowerBase(@JvmField val ex: CrewExtend) {
    val crew: AbstractCrew get() = ex.crew

    @JvmField var enabled = true
    @JvmField var modifiedPowerCharges = -1f
    @JvmField var modifiedChargesPerJump = 0f
    @JvmField var modifiedPowerCooldown = 0f
    @JvmField val powerCooldown = FloatPair()
    @JvmField val powerCharges = IntPair()

    abstract val chargeReq: ActivatedPowerRequirements?
    abstract val disabledCooldownMode: Int

    /** ActivatedPower(Resource)::PowerReq: PowerReqStatic, then POWER_REQ(power, req, state) (Lua). */
    fun powerReq(req: ActivatedPowerRequirements): Int {
        val state = powerReqStatic(req)
        return if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.powerReq(this, this is ActivatedPowerResource, req, state) else state
    }

    /** ActivatedPower::PowerReqStatic. */
    fun powerReqStatic(req: ActivatedPowerRequirements): Int {
        val c = crew
        val currentShip = c.room.ship
        val crewShip = HsCrew.shipById(c, ex.shipId)
        val ownerId = ex.powerOwner
        val ownerShip = HsCrew.shipById(c, ownerId)
        val intruder = ex.intruder

        if (!intruder && req.enemyShip) return PowerReadyState.NOT_READY_ENEMY_SHIP
        if (intruder && req.playerShip) return PowerReadyState.NOT_READY_PLAYER_SHIP
        val sysInRoom = c.room.system
        if (req.systemInRoom && sysInRoom == null) return PowerReadyState.NOT_READY_SYSTEM_IN_ROOM
        else if (req.systemDamaged) {
            if (sysInRoom != null && !sysInRoom.damaged) return PowerReadyState.NOT_READY_SYSTEM_DAMAGED
        }
        if (req.checkRoomCrew) {
            var enemyInRoom = !req.enemyInRoom
            var friendlyInRoom = !req.friendlyInRoom
            var whiteList = req.whiteList.isEmpty()
            var enemyWhiteList = req.enemyWhiteList.isEmpty()
            var friendlyWhiteList = req.friendlyWhiteList.isEmpty()
            var enemyBlackList = true
            var friendlyBlackList = true
            for (i in currentShip.crew) {
                if (i.room !== c.room || i === c || HsCrew.isDead(i)) continue
                val species = i.codename
                if (HsCrew.shipId(i) != ownerId) {
                    enemyInRoom = true
                    if (!whiteList && species in req.whiteList) whiteList = true
                    if (!enemyWhiteList && species in req.enemyWhiteList) enemyWhiteList = true
                    if (enemyBlackList && species in req.enemyBlackList) enemyBlackList = false
                } else {
                    friendlyInRoom = true
                    if (!whiteList && species in req.whiteList) whiteList = true
                    if (!friendlyWhiteList && species in req.friendlyWhiteList) friendlyWhiteList = true
                    if (friendlyBlackList && species in req.friendlyBlackList) friendlyBlackList = false
                }
            }
            if (!enemyInRoom) return PowerReadyState.NOT_READY_ENEMY_IN_ROOM
            else if (!friendlyInRoom) return PowerReadyState.NOT_READY_FRIENDLY_IN_ROOM
            else if (!whiteList) return PowerReadyState.NOT_READY_WHITELIST
            else if (!enemyWhiteList) return PowerReadyState.NOT_READY_ENEMY_WHITELIST
            else if (!friendlyWhiteList) return PowerReadyState.NOT_READY_FRIENDLY_WHITELIST
            else if (!enemyBlackList) return PowerReadyState.NOT_READY_ENEMY_BLACKLIST
            else if (!friendlyBlackList) return PowerReadyState.NOT_READY_FRIENDLY_BLACKLIST
        }
        if ((crewShip == null || crewShip.clonebay == null) && req.hasClonebay) return PowerReadyState.NOT_READY_HAS_CLONEBAY
        if (req.aiDisabled && ownerId == 1) return PowerReadyState.NOT_READY_AI_DISABLED
        val inCombat = crewShip != null && c.room.ship.sys.getEnemyOf(crewShip) != null
        if (req.outOfCombat && crewShip != null && inCombat) return PowerReadyState.NOT_READY_OUT_OF_COMBAT
        if (req.inCombat && crewShip != null && !inCombat) return PowerReadyState.NOT_READY_IN_COMBAT
        if (req.requiredSystem != -1) {
            val sys = ownerShip?.let { xyz.znix.xftl.hs.lua.LuaShipApi.system(it, req.requiredSystem) }
                ?: return PowerReadyState.NOT_READY_SYSTEM
            if (req.requiredSystemFunctional && (sys.isHackActive || HsCrew.effectivePower(sys) == 0))
                return PowerReadyState.NOT_READY_SYSTEM_FUNCTIONAL
        }
        if (req.minHealth.enabled && c.health < req.minHealth.value) return PowerReadyState.NOT_READY_MIN_HEALTH
        if (req.maxHealth.enabled && c.health > req.maxHealth.value) return PowerReadyState.NOT_READY_MAX_HEALTH

        for ((cond, want) in req.extraConditions) {
            if (ex.checkExtraCondition(cond) != want)
                return (if (want) PowerReadyState.NOT_READY_EXTRACONDITION_FALSE else PowerReadyState.NOT_READY_EXTRACONDITION_TRUE) + cond.ordinal
        }
        if (req.extraOrConditions.isNotEmpty()) {
            for ((cond, want) in req.extraOrConditions) if (ex.checkExtraCondition(cond) == want) return PowerReadyState.READY
            return PowerReadyState.NOT_READY_EXTRACONDITION_OR
        }
        return PowerReadyState.READY
    }

    /** The cooldown/charge recharge shared by both OnUpdate methods (speed factor * 0.0625 = dt). */
    protected fun recharge(dt: Float) {
        if (powerCharges.second >= 0 && powerCharges.first <= 0) {
            powerCooldown.first = 0f
        } else {
            val req = chargeReq
            if (req == null || powerReq(req) == PowerReadyState.READY) {
                val mult = ex.calculateStat(CrewStat.POWER_RECHARGE_MULTIPLIER)
                powerCooldown.first = maxOf(0f, minOf(powerCooldown.second, dt * mult + powerCooldown.first))
            }
        }
    }

    protected fun enableInitWith(disabledCooldown: Int, disabledCharges: Int, initialCooldownFraction: Float, initialCharges: Int) {
        when (disabledCooldown) {
            PowerConst.DISABLED_COOLDOWN_FULL -> powerCooldown.first = powerCooldown.second
            PowerConst.DISABLED_COOLDOWN_ZERO -> powerCooldown.first = 0f
            PowerConst.DISABLED_COOLDOWN_RESET -> powerCooldown.first = powerCooldown.second * initialCooldownFraction
        }
        when (disabledCharges) {
            PowerConst.DISABLED_COOLDOWN_FULL -> if (powerCharges.second > 0) powerCharges.first = powerCharges.second
            PowerConst.DISABLED_COOLDOWN_ZERO -> powerCharges.first = 0
            PowerConst.DISABLED_COOLDOWN_RESET -> powerCharges.first = initialCharges
        }
    }
}

class ActivatedPowerResource(@JvmField var def: PowerResourceDefinition, ex: CrewExtend) : PowerBase(ex) {
    @JvmField var lastEnabled = false

    override val chargeReq: ActivatedPowerRequirements? get() = def.chargeReq
    override val disabledCooldownMode: Int get() = def.disabledCooldown

    fun onUpdate(dt: Float) {
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaCrewEvents.resourceOnUpdate(this)) return
        if (enabled || def.disabledCooldown == PowerConst.DISABLED_COOLDOWN_CONTINUE) recharge(dt)
    }

    fun enablePower() { enabled = true }
    fun disablePower() { enabled = false }
    fun enableInit() {
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaCrewEvents.resourceEnableInit(this)) return
        enableInitWith(def.disabledCooldown, def.disabledCharges, def.initialCooldownFraction, def.initialCharges)
    }

    fun save(e: Element) {
        e.setAttribute("def", def.index.toString())
        e.setAttribute("enabled", enabled.toString())
        e.setAttribute("mpc", modifiedPowerCharges.toString())
        e.setAttribute("cd", "${powerCooldown.first},${powerCooldown.second}")
        e.setAttribute("ch", "${powerCharges.first},${powerCharges.second}")
    }

    companion object {
        fun load(e: Element, ex: CrewExtend): ActivatedPowerResource {
            val r = ActivatedPowerResource(CustomCrewManager.resourceDefs[e.getAttributeValue("def").toInt()], ex)
            r.enabled = e.getAttributeValue("enabled").toBoolean()
            r.modifiedPowerCharges = e.getAttributeValue("mpc").toFloat()
            e.getAttributeValue("cd").split(',').let { r.powerCooldown.first = it[0].toFloat(); r.powerCooldown.second = it[1].toFloat() }
            e.getAttributeValue("ch").split(',').let { r.powerCharges.first = it[0].toInt(); r.powerCharges.second = it[1].toInt() }
            return r
        }
    }
}

class ActivatedPower(@JvmField var def: ActivatedPowerDefinition, ex: CrewExtend) : PowerBase(ex) {
    @JvmField var enabledInit = false
    @JvmField var tempEnabled = false
    @JvmField var activateWhenReady = false

    @JvmField val temporaryPowerDuration = FloatPair()
    @JvmField val powerResources = ArrayList<ActivatedPowerResource>()

    @JvmField var powerRoom = -1
    @JvmField var powerShip = -1
    @JvmField var powerActivated = false
    @JvmField var temporaryPowerActive = false
    @JvmField var powerDone = true
    @JvmField var temporaryPowerDone = true

    @JvmField var effectAnim: FTLAnimation? = null
    @JvmField var tempEffectAnim: FTLAnimation? = null
    @JvmField var effectFinishAnim: FTLAnimation? = null
    @JvmField val extraAnims = ArrayList<FTLAnimation>()
    @JvmField var effectPosX = 0f
    @JvmField var effectPosY = 0f
    @JvmField var effectWorldPosX = 0f
    @JvmField var effectWorldPosY = 0f

    override val chargeReq: ActivatedPowerRequirements? get() = def.chargeReq
    override val disabledCooldownMode: Int get() = def.disabledCooldown

    private val game get() = crew.room.ship.sys

    private fun anim(name: String): FTLAnimation? {
        if (name.isEmpty()) return null
        val spec = game.animations.getOrNull(name)
        if (spec == null) {
            xyz.znix.xftl.hs.HS.unsupported("anim", "crew power", name)
            return null
        }
        return spec.startSingle(game)
    }

    /** ActivatedPower::PowerReady: the state, then POWER_READY(power, state) (Lua; not while a save loads). */
    fun powerReady(): Int {
        if (game.isCurrentlyLoadingSave) return PowerReadyState.NOT_READY_COOLDOWN
        val state = powerReadyState()
        return if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.powerReady(this, state) else state
    }

    private fun powerReadyState(): Int {
        if (temporaryPowerActive) return PowerReadyState.NOT_READY_ACTIVATED
        if (powerCharges.second >= 0 && powerCharges.first <= 0) return PowerReadyState.NOT_READY_CHARGES
        for (r in powerResources) if (r.powerCharges.second >= 0 && r.powerCharges.first <= 0) return PowerReadyState.NOT_READY_CHARGES
        if (powerCooldown.first < powerCooldown.second) return PowerReadyState.NOT_READY_COOLDOWN
        for (r in powerResources) if (r.powerCooldown.first < r.powerCooldown.second) return PowerReadyState.NOT_READY_COOLDOWN
        if (ex.calculateBool(CrewStat.SILENCED, ex.definition())) return PowerReadyState.NOT_READY_SILENCED
        return powerReq(if (ex.powerOwner == 0) def.playerReq else def.enemyReq)
    }

    /** ActivatedPower::GetPowerDamage. */
    fun powerDamage(): HsDamage = def.damage.copy(ownerId = ex.powerOwner, selfId = ex.selfId)

    fun activateTemporaryPower() {
        temporaryPowerDone = true
        prepareTemporaryAnimation()
        tempEffectAnim?.let { it.loop = true; it.timer = 0f }
        if (def.hasTemporaryPower) {
            temporaryPowerActive = true
            temporaryPowerDuration.first = def.tempPower.duration
            temporaryPowerDuration.second = def.tempPower.duration
        }
        ex.updateAbilityStatBoosts()
        StatBoostManager.statCacheFrame++
    }

    fun temporaryPowerFinished() {
        temporaryPowerActive = false
        val sounds = def.tempPower.sounds
        if (sounds.isNotEmpty() && (def.tempPower.soundsEnemy || ex.shipId == 0))
            HsCrew.playSound(game, sounds[HsCrew.random32() % sounds.size])
        tempEffectAnim = null
        effectFinishAnim?.let { it.loop = false; it.timer = 0f }
        ex.updateAbilityStatBoosts()
        StatBoostManager.statCacheFrame++
    }

    fun prepareAnimation() { effectAnim = anim(def.effectAnim) }

    fun prepareTemporaryAnimation() {
        if (temporaryPowerActive) {
            // A changed definition keeps the old animations' progress.
            val oldFinish = effectFinishAnim
            effectFinishAnim = anim(def.tempPower.effectFinishAnim)?.also { n ->
                if (oldFinish != null && oldFinish.duration > 0f) n.timer = oldFinish.timer / oldFinish.duration * n.duration
            }
            val oldTemp = tempEffectAnim
            tempEffectAnim = anim(def.tempPower.effectAnim)?.also { n ->
                n.loop = oldTemp?.loop ?: false
                if (oldTemp != null && oldTemp.duration > 0f) n.timer = oldTemp.timer / oldTemp.duration * n.duration
            }
        } else {
            effectFinishAnim = anim(def.tempPower.effectFinishAnim)
            tempEffectAnim = anim(def.tempPower.effectAnim)
        }
    }

    /** ActivatedPower::PreparePower (PREPARE_POWER(power) first; a PREEMPT skips it). */
    fun preparePower() {
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaCrewEvents.preparePower(this)) return
        prepareAnimation()
        powerDone = false
        powerCooldown.first = 0f
        powerCharges.first = maxOf(0, powerCharges.first - 1)
        for (r in powerResources) {
            r.powerCooldown.first = 0f
            r.powerCharges.first = maxOf(0, r.powerCharges.first - 1)
        }
        val ownerShip = ex.powerOwner
        powerShip = ex.currentShipId
        powerRoom = ex.roomId
        val centre = crew.getPixelPositionCentre()
        effectWorldPosX = centre.x.toFloat()
        effectWorldPosY = centre.y.toFloat()

        if (def.animFrame == -1) activatePower()
        if (def.tempPower.animFrame == -1) activateTemporaryPower() else temporaryPowerDone = false

        effectAnim?.let { it.loop = false; it.timer = 0f }

        if (def.sounds.isNotEmpty() && (def.soundsEnemy || ex.shipId == 0 || ownerShip == 0))
            HsCrew.playSound(game, def.sounds[HsCrew.random32() % def.sounds.size])

        effectAnim?.let {
            effectPosX = centre.x - it.width / 2f
            effectPosY = centre.y - it.height / 2f + crew.hsPositionShift
        }
    }

    /**
     * ActivatedPower::ActivatePower's win effect (CrewAbilities.cpp): StopPlaylist(100), the victory sound,
     * ScoreKeeper::SetVictory, the custom victory type cleared, AchievementTracker::SetVictoryAchievement, gameover and
     * CommandGui::Victory. SetVictoryAchievement is the ship's victory alone (1.6.9 0x430040, no ACH_WIN_*); the victory
     * screen does gameover, Victory and the HS unlock hooks.
     */
    private fun win() {
        game.sounds.stopPlaylist(0.1f)
        HsCrew.playSound(game, "victory")
        // CustomShipUnlocks::setCustomVictoryType = "": SetVictoryAchievement skips the DEFEAT_FLAGSHIP unlocks and the
        // typed victory (MV's "Multiversal Victory"), and resets it to "flagship" itself.
        xyz.znix.xftl.hs.ship.HsShipUnlocks.setCustomVictoryType = ""
        xyz.znix.xftl.hs.events.HsFinalBoss.victoryType = ""
        game.browserProgress.shipVictoryOnly()
        game.shipUI.showGameOverScreen(xyz.znix.xftl.game.GameOverWindow.Outcome.WIN)
    }

    /** ActivatedPower::ActivatePower (ACTIVATE_POWER(power, ship) first; a PREEMPT skips it). */
    fun activatePower() {
        val ship = HsCrew.shipById(crew, powerShip)
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaCrewEvents.activatePower(this, ship)) return
        powerActivated = true

        if (def.damage.bLockdown) {
            HsCrew.lockdown(crew, def.customLockdown)
        }
        if (def.win) win()

        val ownerShip = ex.powerOwner
        if (def.effectSounds.isNotEmpty() && (def.effectSoundsEnemy || ex.shipId == 0 || ownerShip == 0))
            HsCrew.playSound(game, def.effectSounds[HsCrew.random32() % def.effectSounds.size])

        if ((def.crewHealth != 0f || def.enemyHealth != 0f) && ship != null) {
            for (i in ship.crew.toList()) {
                if (i.room.id == powerRoom && i !== crew) {
                    if (HsCrew.shipId(i) == ownerShip) HsCrew.directModifyHealth(i, def.crewHealth)
                    else HsCrew.directModifyHealth(i, def.enemyHealth)
                }
            }
        }
        if (ex.roomId == powerRoom && ex.currentShipId == powerShip && def.selfHealth != 0f)
            HsCrew.directModifyHealth(crew, def.selfHealth)

        powerDone = true

        anim(def.effectPostAnim)?.let { a ->
            a.loop = false
            extraAnims.add(a)
        }

        if (def.transformRace.isNotEmpty()) {
            ex.originalRace = def.transformRace
            ex.transformRaceTo(def.transformRace)
        }

        if (def.statBoosts.isNotEmpty()) {
            for (id in 0..1) {
                val s = HsCrew.shipById(crew, id) ?: continue
                for (other in s.crew.toList()) for (sd in def.statBoosts) {
                    val b = StatBoost(sd)
                    b.crewSource = crew
                    b.crewSourceId = ex.selfId
                    b.sourceShipId = ownerShip
                    StatBoostManager.createTimedAugmentBoost(b, other)
                }
            }
        }

        if (def.roomStatBoosts.isNotEmpty() && ship != null) {
            val room = ship.rooms.getOrNull(powerRoom)
            if (room != null) for (sd in def.roomStatBoosts) StatBoostManager.createRoomStatBoost(sd, room, 1, crew)
        }

        if (ship != null) for (spawn in def.crewSpawns)
            HsCrew.spawnCrew(spawn, ship, HsCrew.shipIdOf(ship) != ownerShip, effectWorldPosX, effectWorldPosY)

        if (def.event[ownerShip].isNotEmpty()) HsCrew.queueEvent(def.event[ownerShip])
    }

    /** ActivatedPower::CancelPower (CANCEL_POWER(power, clearAnim) first; a PREEMPT skips it). */
    fun cancelPower(clearAnim: Boolean) {
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaCrewEvents.cancelPower(this, clearAnim)) return
        if (!powerDone || clearAnim) {
            effectAnim = null
            powerDone = true
        }
        temporaryPowerDone = true
        if (temporaryPowerActive) {
            temporaryPowerDuration.first = 0f
            temporaryPowerFinished()
        }
    }

    /** ActivatedPower::OnUpdate (POWER_ON_UPDATE(power) after the death cancel; a PREEMPT skips the rest). */
    fun onUpdate(dt: Float) {
        val dying = crew.currentAction == AbstractCrew.Action.DYING
        if (dying && def.onDeath == PowerConst.ON_DEATH_CANCEL) cancelPower(false)
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaCrewEvents.powerOnUpdate(this)) return

        if (temporaryPowerActive) {
            temporaryPowerDuration.first = maxOf(0f, temporaryPowerDuration.first - dt)
            if (temporaryPowerDuration.first <= 0f) temporaryPowerFinished()
        } else if (enabled || def.disabledCooldown == PowerConst.DISABLED_COOLDOWN_CONTINUE) {
            recharge(dt)
            if (enabled && !HsCrew.isDead(crew) && ex.functional && !dying) {
                if (activateWhenReady) {
                    if (powerReady() == PowerReadyState.READY) preparePower()
                } else {
                    // Vanilla condition (CrewMember::OnLoop's AI power use).
                    val target = crew.enemyToAttack
                    if (target != null && crew.canFight && target is LivingCrew && powerReady() == PowerReadyState.READY &&
                        ex.powerOwner == 1 && crew.health > 0.5f * crew.maxHealth
                    ) {
                        if (!crew.room.isLockedDown) preparePower()
                    }
                }
            }
        }

        if (!powerDone && def.followCrew) {
            powerShip = ex.currentShipId
            powerRoom = ex.roomId
            val centre = crew.getPixelPositionCentre()
            effectAnim?.let { effectPosX = centre.x - it.width / 2f; effectPosY = centre.y - it.height / 2f + crew.hsPositionShift }
            effectWorldPosX = centre.x.toFloat()
            effectWorldPosY = centre.y.toFloat()
        }

        effectAnim?.let { a ->
            a.update(dt)
            val running = !a.isStopped
            if (!powerDone && def.animFrame != -1 && running && a.frame >= def.animFrame) activatePower()
            if (!temporaryPowerDone && def.tempPower.animFrame != -1 && running && a.frame >= def.tempPower.animFrame)
                activateTemporaryPower()
        }
        tempEffectAnim?.update(dt)
        effectFinishAnim?.update(dt)
        val it = extraAnims.iterator()
        while (it.hasNext()) {
            val a = it.next()
            a.update(dt)
            if (a.isStopped) it.remove()
        }
    }

    /** ActivatedPower::ChangePowerDef. */
    fun changePowerDef(newDef: ActivatedPowerDefinition) {
        if (!newDef.hasTemporaryPower && temporaryPowerActive) {
            temporaryPowerDuration.first = 0f
            temporaryPowerFinished()
        }
        def = newDef
        if (newDef.hasTemporaryPower && temporaryPowerActive) {
            prepareTemporaryAnimation()
            temporaryPowerDuration.first = def.tempPower.duration * (temporaryPowerDuration.first / temporaryPowerDuration.second)
            temporaryPowerDuration.second = def.tempPower.duration
            ex.updateAbilityStatBoosts()
            StatBoostManager.statCacheFrame++
        }
        powerResources.clear()
        linkPowerResources()
    }

    fun enablePower() { enabled = true; enabledInit = true }

    fun enableInit() {
        // POWER_ENABLE_INIT(power): a PREEMPT skips the cooldown/charge reset, enabledInit is cleared either way
        if (!(xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaCrewEvents.powerEnableInit(this)))
            enableInitWith(def.disabledCooldown, def.disabledCharges, def.initialCooldownFraction, def.initialCharges)
        enabledInit = false
    }

    fun disablePower() {
        enabled = false
        if (temporaryPowerActive) {
            temporaryPowerDuration.first = 0f
            temporaryPowerFinished()
        }
    }

    /** ActivatedPower::LinkPowerResources. */
    fun linkPowerResources() {
        for (rd in def.powerResources) {
            if (rd == null) continue
            var r = ex.powerResourceMap[rd.index]
            if (r == null) {
                r = ActivatedPowerResource(rd, ex)
                ex.powerResources.add(r)
                ex.powerResourceMap[rd.index] = r
                ex.tempAddedPowerResource = true
                r.powerCooldown.first = rd.cooldown * rd.initialCooldownFraction
                r.powerCooldown.second = rd.cooldown
                r.powerCharges.first = rd.initialCharges
                r.powerCharges.second = -1
                r.modifiedPowerCharges = rd.powerCharges.toFloat()
            }
            r.enabled = true
            powerResources.add(r)
        }
    }

    fun enablePowerResources() { for (r in powerResources) r.enablePower() }

    fun save(e: Element) {
        e.setAttribute("def", def.index.toString())
        e.setAttribute("enabled", enabled.toString())
        e.setAttribute("mpc", modifiedPowerCharges.toString())
        e.setAttribute("cd", "${powerCooldown.first},${powerCooldown.second}")
        e.setAttribute("tmp", "${temporaryPowerDuration.first},${temporaryPowerDuration.second}")
        e.setAttribute("ch", "${powerCharges.first},${powerCharges.second}")
        e.setAttribute("room", "$powerRoom,$powerShip")
        e.setAttribute("flags", "$powerActivated,$temporaryPowerActive,$powerDone,$temporaryPowerDone")
        e.setAttribute("pos", "$effectPosX,$effectPosY,$effectWorldPosX,$effectWorldPosY")
        effectAnim?.let { e.setAttribute("effectAnim", it.timer.toString()) }
        tempEffectAnim?.let { e.setAttribute("tempEffectAnim", it.timer.toString()) }
        effectFinishAnim?.let { e.setAttribute("effectFinishAnim", it.timer.toString()) }
    }

    companion object {
        fun load(e: Element, ex: CrewExtend): ActivatedPower {
            val p = ActivatedPower(CustomCrewManager.powerDefs[e.getAttributeValue("def").toInt()], ex)
            p.enabled = e.getAttributeValue("enabled").toBoolean()
            p.modifiedPowerCharges = e.getAttributeValue("mpc").toFloat()
            e.getAttributeValue("cd").split(',').let { p.powerCooldown.first = it[0].toFloat(); p.powerCooldown.second = it[1].toFloat() }
            e.getAttributeValue("tmp").split(',').let { p.temporaryPowerDuration.first = it[0].toFloat(); p.temporaryPowerDuration.second = it[1].toFloat() }
            e.getAttributeValue("ch").split(',').let { p.powerCharges.first = it[0].toInt(); p.powerCharges.second = it[1].toInt() }
            e.getAttributeValue("room").split(',').let { p.powerRoom = it[0].toInt(); p.powerShip = it[1].toInt() }
            e.getAttributeValue("flags").split(',').let {
                p.powerActivated = it[0].toBoolean(); p.temporaryPowerActive = it[1].toBoolean()
                p.powerDone = it[2].toBoolean(); p.temporaryPowerDone = it[3].toBoolean()
            }
            e.getAttributeValue("pos").split(',').let {
                p.effectPosX = it[0].toFloat(); p.effectPosY = it[1].toFloat()
                p.effectWorldPosX = it[2].toFloat(); p.effectWorldPosY = it[3].toFloat()
            }
            e.getAttributeValue("effectAnim")?.let { t -> p.prepareAnimation(); p.effectAnim?.let { it.loop = false; it.timer = t.toFloat() } }
            val tempT = e.getAttributeValue("tempEffectAnim")
            val finT = e.getAttributeValue("effectFinishAnim")
            val wasActive = p.temporaryPowerActive
            p.temporaryPowerActive = false
            p.prepareTemporaryAnimation()
            p.temporaryPowerActive = wasActive
            if (tempT != null) p.tempEffectAnim?.let { it.loop = true; it.timer = tempT.toFloat() } else p.tempEffectAnim = null
            if (finT != null) p.effectFinishAnim?.let { it.loop = false; it.timer = finT.toFloat() } else p.effectFinishAnim = null
            return p
        }
    }
}

/** CrewMember_Extend's power functions (CrewAbilities.cpp, CrewVTable.cpp, CustomCrew.cpp). */
internal object CrewPowerLogic {
    /** CrewMember_Extend::CalculatePowerDef. */
    fun calculatePowerDef(ex: CrewExtend) {
        val def = CustomCrewManager.definition(ex.species)
        ex.calculateStat(CrewStat.POWER_EFFECT, def)

        for (p in ex.crewPowers) p.tempEnabled = false
        for (r in ex.powerResources) { r.lastEnabled = r.enabled; r.enabled = false }
        ex.hasSpecialPower = false
        var powerChanged = false
        ex.tempAddedPowerResource = false
        val change = ex.powerChange

        // Loop 1: existing powers matching exact definitions
        run {
            var i = 0
            outer@ while (i < change.size) {
                val pd = change[i]
                for (p in ex.crewPowers) {
                    if (!p.tempEnabled && p.enabled && p.def === pd) {
                        ex.hasSpecialPower = true
                        p.tempEnabled = true
                        p.enablePowerResources()
                        change.removeAt(i)
                        continue@outer
                    }
                }
                i++
            }
        }

        if (change.isNotEmpty()) {
            // Loop 2: enabled powers in the same replace group
            var i = 0
            outer@ while (i < change.size) {
                val pd = change[i]
                if (pd != null && pd.replaceGroupIndex != 0) {
                    for (p in ex.crewPowers) {
                        if (!p.tempEnabled && p.enabled && p.def.replaceGroupIndex == pd.replaceGroupIndex) {
                            ex.hasSpecialPower = true; p.tempEnabled = true; powerChanged = true
                            p.changePowerDef(pd)
                            change.removeAt(i)
                            continue@outer
                        }
                    }
                }
                i++
            }
            // Loop 3: dormant powers with the exact definition
            i = 0
            outer@ while (i < change.size) {
                val pd = change[i]
                for (p in ex.crewPowers) {
                    if (!p.tempEnabled && p.def === pd) {
                        ex.hasSpecialPower = true; p.tempEnabled = true
                        p.enablePower(); p.enablePowerResources()
                        powerChanged = true
                        change.removeAt(i)
                        continue@outer
                    }
                }
                i++
            }
            // Loop 4: dormant powers in the same replace group
            i = 0
            outer@ while (i < change.size) {
                val pd = change[i]
                if (pd != null && pd.replaceGroupIndex != 0) {
                    for (p in ex.crewPowers) {
                        if (!p.tempEnabled && p.def.replaceGroupIndex == pd.replaceGroupIndex) {
                            ex.hasSpecialPower = true; p.tempEnabled = true
                            p.enablePower(); powerChanged = true
                            p.changePowerDef(pd)
                            change.removeAt(i)
                            continue@outer
                        }
                    }
                }
                i++
            }
        }

        for (p in ex.crewPowers) {
            if (!p.tempEnabled && p.enabled) { p.disablePower(); powerChanged = true }
        }

        if (change.isNotEmpty()) {
            ex.hasSpecialPower = true
            powerChanged = true
            for (pd in change) {
                if (pd == null) continue // HS would crash on a null definition
                val p = ActivatedPower(pd, ex)
                ex.crewPowers.add(p)
                p.powerCooldown.first = pd.cooldown * pd.initialCooldownFraction
                p.powerCooldown.second = pd.cooldown
                p.powerCharges.first = pd.initialCharges
                p.powerCharges.second = -1
                p.modifiedPowerCharges = pd.powerCharges.toFloat()
                p.linkPowerResources()
            }
        }

        if (powerChanged) {
            // std::stable_sort: Kotlin's sortWith is stable.
            ex.crewPowers.sortWith { a, b -> a.def.sortOrder.compareTo(b.def.sortOrder) }
            if (ex.tempAddedPowerResource) ex.powerResources.sortWith { a, b -> a.def.sortOrder.compareTo(b.def.sortOrder) }
            HsCrew.clearCrewBoxes(ex)
        }

        if (ex.crewPowers.isNotEmpty() || ex.powerResources.isNotEmpty()) {
            ex.calculateStat(CrewStat.POWER_MAX_CHARGES, def)
            ex.calculateStat(CrewStat.POWER_COOLDOWN, def)
            ex.calculateStat(CrewStat.ACTIVATE_WHEN_READY, def)

            // HS writes `!enabled && !disabledCooldown != CONTINUE`: `!disabledCooldown` is 0/1, never 2, so the
            // test is just `!enabled` and disabled powers always skip.
            for (p in ex.crewPowers) {
                if (!p.enabled) continue
                applyCooldown(p.powerCooldown, p.modifiedPowerCooldown)
            }
            for (r in ex.powerResources) {
                if (!r.enabled) continue
                applyCooldown(r.powerCooldown, r.modifiedPowerCooldown)
            }
            for (p in ex.crewPowers) {
                if (!p.enabled) continue
                applyCharges(p.powerCharges, p.modifiedPowerCharges)
            }
            for (r in ex.powerResources) {
                if (!r.enabled) continue
                applyCharges(r.powerCharges, r.modifiedPowerCharges)
            }
            for (p in ex.crewPowers) if (p.enabledInit) p.enableInit()
            for (r in ex.powerResources) if (r.enabled && !r.lastEnabled) r.enableInit()
        }
    }

    private fun applyCooldown(cd: FloatPair, modified: Float) {
        if (cd.second != modified) {
            if (cd.second > 0f) cd.first = modified * (cd.first / cd.second) else cd.first = modified
            cd.second = modified
        }
    }

    private fun applyCharges(ch: IntPair, modified: Float) {
        // (int) of a float >= 2^31 is -1 in HS's own test; below that it truncates.
        val max = if (modified >= 2147483648f) -1 else modified.toInt()
        if (ch.second != max) {
            ch.second = max
            if (max != -1) ch.first = minOf(ch.first, max)
        }
    }

    /** CrewMember_Extend::UpdateAbilityStatBoosts. */
    fun updateAbilityStatBoosts(ex: CrewExtend, def: CrewDefinition?) {
        ex.outgoingStatBoosts.clear()
        var noActiveAbility = true
        val shipId = ex.shipId
        for (p in ex.crewPowers) {
            if (!p.temporaryPowerActive) continue
            noActiveAbility = false
            for (sd in p.def.tempPower.statBoosts) ex.outgoingStatBoosts.add(own(ex, sd, shipId))
        }
        if (noActiveAbility && def != null) {
            for (sd in def.passiveStatBoosts) ex.outgoingStatBoosts.add(own(ex, sd, shipId))
        }
    }

    private fun own(ex: CrewExtend, sd: xyz.znix.xftl.hs.stat.StatBoostDefinition, shipId: Int) = StatBoost(sd).also {
        it.crewSource = ex.crew
        it.crewSourceId = ex.selfId
        it.sourceShipId = shipId
    }

    /** CrewMember::_HS_ResetPower (called on an FTL jump). */
    fun resetPower(ex: CrewExtend) {
        if (ex.crewPowers.isEmpty()) return
        ex.calculateStat(CrewStat.POWER_CHARGES_PER_JUMP, ex.definition())
        for (p in ex.crewPowers) {
            if (!p.enabled) continue
            when (p.def.jumpCooldown) {
                PowerConst.JUMP_COOLDOWN_FULL -> p.powerCooldown.first = p.powerCooldown.second
                PowerConst.JUMP_COOLDOWN_RESET -> p.powerCooldown.first = 0f
            }
            p.powerCharges.first = minOf(p.powerCharges.second, p.powerCharges.first + p.modifiedChargesPerJump.toInt())
        }
    }

    /** The CrewMember::Restart hook (clone or drone redeploy). */
    fun restart(ex: CrewExtend) {
        for (p in ex.crewPowers) {
            if (p.def.onDeath == PowerConst.ON_DEATH_CANCEL || p.def.onDeath == PowerConst.ON_DEATH_RESET) p.cancelPower(true)
            if (p.enabled || p.def.disabledCharges == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                p.powerCharges.first = maxOf(0, minOf(p.powerCharges.first + p.def.respawnCharges, p.powerCharges.second))
        }
        for (r in ex.powerResources) {
            if (r.enabled || r.def.disabledCharges == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                r.powerCharges.first = maxOf(0, minOf(r.powerCharges.first + r.def.respawnCharges, r.powerCharges.second))
        }
    }
}
