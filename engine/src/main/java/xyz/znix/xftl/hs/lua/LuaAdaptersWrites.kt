// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i members), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.GameText
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.hs.crew.ActivatedPowerDefinition
import xyz.znix.xftl.hs.crew.ActivatedPowerRequirements
import xyz.znix.xftl.hs.crew.CrewDefinition
import xyz.znix.xftl.hs.crew.HsColour
import xyz.znix.xftl.hs.crew.PowerResourceDefinition
import xyz.znix.xftl.hs.crew.TemporaryPowerDefinition
import xyz.znix.xftl.hs.crew.Toggle
import xyz.znix.xftl.hs.ship.CrewPlacement
import xyz.znix.xftl.hs.ship.CustomShipDefinition
import xyz.znix.xftl.hs.ship.RoomDefinition
import xyz.znix.xftl.hs.lua.LuaApi as A

/** A GL_Color member over a definition's colour: a field write reaches the definition. */
class LuaDefColor(private val c: () -> HsColour) : GlColorValue(c().r, c().g, c().b, c().a), LuaWriteThrough {
    override fun written() { val d = c(); d.r = r; d.g = g; d.b = b; d.a = a }
}

/** A vector member over engine state: holds its own elements and reports every change. */
class LuaTrackedList(initial: List<Any?>, private val changed: (List<Any?>) -> Unit) : java.util.AbstractList<Any?>() {
    private val own = ArrayList<Any?>(initial)
    override val size get() = own.size
    override fun get(index: Int) = own[index]
    override fun set(index: Int, element: Any?): Any? = own.set(index, element).also { changed(own) }
    override fun add(index: Int, element: Any?) { own.add(index, element); changed(own) }
    override fun removeAt(index: Int): Any? = own.removeAt(index).also { changed(own) }
}

/** A Point member the engine computes: it holds a script's value until the engine's own changes. */
class LuaHeldPoint(@JvmField var engineX: Int, @JvmField var engineY: Int) : PointValue(engineX, engineY), LuaWriteThrough {
    @JvmField var onWrite: (() -> Unit)? = null
    override fun written() { onWrite?.invoke() }
}

/** Location::loc: the beacon's place on the map. */
class LuaBeaconLoc(private val b: xyz.znix.xftl.sector.Beacon) : PointfValue(b.pos.x.toFloat(), b.pos.y.toFloat()), LuaWriteThrough {
    override fun written() { b.pos = xyz.znix.xftl.math.ConstPoint(x.toInt(), y.toInt()) }
}

/**
 * The members hyperspace.i leaves writable whose reads come from engine objects (definitions are shared by everything built
 * from them, so a write reaches them all as in the original). Installed after [LuaAdaptersWritable].
 */
object LuaAdaptersWrites {
    @JvmStatic
    fun install() {
        temporaryPower()
        requirements()
        activatedPower()
        crewDefinition()
        shipDefinition()
        shipBlueprint()
        weaponBlueprint()
        systems()
        recomputed()
        shipFields()
        LuaAdaptersWritesPictures.install()
    }

    /** ShipSystem::OnRenderFloor's interior primitive: a script made it at [A.A_ShipSystem_pLoc], drawn at the room here. */
    @JvmStatic
    fun drawInterior(g: xyz.znix.xftl.rendering.Graphics, s: xyz.znix.xftl.AbstractSystem, p: LuaPrimitive?, x: Int, y: Int) {
        p ?: return
        val loc = LuaReg.objGet[A.A_ShipSystem_pLoc]?.get(s)
        val lx = (loc as? PointValue)?.x ?: x; val ly = (loc as? PointValue)?.y ?: y
        g.pushTransform()
        g.translate((x - lx).toFloat(), (y - ly).toFloat())
        LuaGraphics.renderPrimitive(p, xyz.znix.xftl.rendering.Colour(1f, 1f, 1f, 0.75f))
        g.popTransform()
    }

    /** Members the original writes again in its next frame (oracle hw-kw, hw-kc): a write holds until the frame ends. */
    private fun recomputed() {
        for (a in intArrayOf(A.A_WeaponAnimation_bFiring, A.A_WeaponAnimation_fChargeLevel, A.A_WeaponAnimation_bPowered, A.A_WeaponAnimation_slideDirection,
            A.A_WeaponAnimation_fActualChargeLevel, A.A_WeaponAnimation_iHackLevel, A.A_WeaponMount_gib, A.A_HackingSystem_spendDrone,
            A.A_ArmamentBox_hotKey, A.A_ArmamentBox_bIoned, A.A_ArtillerySystem_bCloaked))
            LuaReg.numSet[a] = NumSet { o, v -> LuaStored.frame(o, a, if (A.attrKind(a) == 2) v != 0.0 else v) }
        for (a in intArrayOf(A.A_CloakingSystem_soundeffect, A.A_BatterySystem_soundeffect))
            LuaReg.strSet[a] = StrSet { o, v -> LuaStored.frame(o, a, v) }
    }

    /** Ship state read from members a script can move (oracle hw-ks, hw-kc). */
    private fun shipFields() {
        fun ship(o: Any?) = (o as xyz.znix.xftl.AbstractSystem).room?.ship
        LuaReg.numSet[A.A_WeaponSystem_slot_count] = NumSet { o, v -> LuaStored.put(o, A.A_WeaponSystem_slot_count, v); ship(o)?.hsWeaponSlots = v.toInt() }
        LuaReg.numSet[A.A_DroneSystem_slot_count] = NumSet { o, v -> LuaStored.put(o, A.A_DroneSystem_slot_count, v); ship(o)?.hsDroneSlots = v.toInt() }
        LuaReg.numSet[A.A_TeleportSystem_iNumSlots] = NumSet { o, v -> (o as xyz.znix.xftl.systems.Teleporter).hsNumSlotsWritten = v.toInt() }
        val visited = LuaReg.numGet[A.A_Sector_visited]
        LuaReg.numSet[A.A_Sector_visited] = NumSet { o, v ->
            val info = LuaAdaptersWP6Map.sectorInfo(o)
            val game = LuaAdapters.inGame()
            game?.hsSetVisited(info, v != 0.0)
            if (visited == null) LuaStored.put(o, A.A_Sector_visited, v != 0.0)
        }
    }

    private fun systems() {
        fun s(o: Any?) = o as xyz.znix.xftl.AbstractSystem
        for ((i, a) in intArrayOf(A.A_ShipSystem_interiorImage, A.A_ShipSystem_interiorImageOn, A.A_ShipSystem_interiorImageManned, A.A_ShipSystem_interiorImageMannedFancy).withIndex()) {
            val get = LuaReg.objGet[a]
            LuaReg.objSet[a] = ObjSet { o, v ->
                val images = s(o).hsLuaInterior ?: arrayOfNulls<LuaPrimitive>(4).also { n ->
                    // what the system had: a later write of one member leaves the others
                    intArrayOf(A.A_ShipSystem_interiorImage, A.A_ShipSystem_interiorImageOn, A.A_ShipSystem_interiorImageManned, A.A_ShipSystem_interiorImageMannedFancy)
                        .forEachIndexed { k, m -> n[k] = LuaReg.objGet[m]?.get(o) as? LuaPrimitive }
                    n.forEachIndexed { k, q -> if (q != null && q.texture == null) n[k] = null }
                    s(o).hsLuaInterior = n
                }
                images[i] = v as? LuaPrimitive
                LuaStored.put(o, a, v)
            }
            if (get == null) continue
        }
        embedded(A.A_ShipSystem_powerState, A.A_ShipSystem_healthState, A.A_ShipSystem_lockTimer)
        // Projectile::flight_animation = an Animation by value (AnimationControl::GetAnimation): the body flies with it.
        LuaReg.objSet[A.A_Projectile_flight_animation] = ObjSet { o, v ->
            val p = o as xyz.znix.xftl.weapons.AbstractProjectile
            val spec = (v as? LuaStruct)?.values?.get(LuaAnim.SPEC) as? xyz.znix.xftl.AnimationSpec
            if (spec != null) {
                p.hsFlightSpec = spec
                val t = p.flightTracker
                t.time = spec.totalTime; t.frameCount = spec.length; t.loop = false; t.running = false
                (LuaStructs.member(LuaProjectileAnimation.view(t), A.A_Animation_info) as LuaStruct).values[A.A_AnimationDescriptor_numFrames] = spec.length.toDouble()
                p.hsFlightName = spec.name
            }
        }
        // bTurnedOn: the battery gives its power until something turns it off (no timer was started: oracle hw-bt).
        LuaReg.numSet[A.A_BatterySystem_bTurnedOn] = NumSet { o, v -> (o as xyz.znix.xftl.systems.BackupBattery).hsWriteTurnedOn(v != 0.0) }
        LuaReg.objSet[A.A_ArtillerySystem_projectileFactory] = ObjSet { o, v ->
            (v as? xyz.znix.xftl.weapons.AbstractWeaponInstance)?.let { (o as xyz.znix.xftl.systems.Artillery).hsSetWeapon(it) }
        }
        // CrewMember_Extend timers: the crew code runs what is assigned here.
        fun ex(o: Any?) = o as xyz.znix.xftl.hs.crew.CrewExtend
        fun timer(v: Any?): xyz.znix.xftl.hs.stat.HsTimer? = when (v) {
            null -> null
            is xyz.znix.xftl.hs.stat.HsTimer -> v
            is LuaTimerView -> xyz.znix.xftl.hs.stat.HsTimer().also { it.currTime = v.currTime; it.currGoal = v.currGoal; it.running = v.running && v.currGoal > 0f }
            else -> throw LuaError("expected TimerHelper")
        }
        LuaReg.objSet[A.A_CrewMember_Extend_deathTimer] = ObjSet { o, v -> ex(o).deathTimer = timer(v) }
        LuaReg.objSet[A.A_CrewMember_Extend_passiveHealTimer] = ObjSet { o, v -> ex(o).passiveHealTimer = timer(v) }
        embedded(A.A_CrewMember_Extend_powerChange, A.A_ActivatedPower_powerCooldown, A.A_ActivatedPower_temporaryPowerDuration, A.A_ActivatedPower_powerCharges,
            A.A_ActivatedPower_extraAnims, A.A_ActivatedPowerResource_powerCooldown, A.A_ActivatedPowerResource_powerCharges,
            A.A_PowerManager_currentPower, A.A_PowerManager_batteryPower, A.A_Ship_hullIntegrity, A.A_CloakingSystem_timer, A.A_HackingSystem_effectTimer,
            A.A_MindSystem_controlTimer, A.A_MindSystem_controlledCrew, A.A_MindSystem_queuedCrew, A.A_ProjectileFactory_subCooldown, A.A_OxygenSystem_oxygenLevels,
            A.A_TeleportSystem_crewSlots, A.A_Shields_center, A.A_Shields_shields, A.A_Shields_superUpLoc, A.A_Shields_shieldsDownPoint,
            A.A_Location_loc)
        // SpaceManager::currentBack: the backdrop drawn from the next frame (MV's events.lua puts a cached one back).
        LuaReg.objSet[A.A_SpaceManager_currentBack] = ObjSet { _, v ->
            LuaAdapters.inGame()?.let { g -> g.currentBeacon.getEnvironment(g).hsSetBackdrop((v as? LuaTexture)?.path) }
        }
    }

    /** A script moved a crew member off its ship ([A.A_CrewMember_currentShipId] or bDead): CountCrew has something to skip. */
    @JvmField var movedAway = false

    /** ShipManager's crew loop drops a member whose currentShipId is not the ship's: it stays a valid object, on no ship's list. */
    fun leaveShip(x: xyz.znix.xftl.crew.AbstractCrew) {
        val ship = x.room.ship
        if (ship.crew.remove(x)) ship.updateCrewReservedSlots()
    }

    /** `owner.member = value` for a member whose read is the engine's own object: the value is copied through the fields' setters. */
    fun embedded(vararg attrs: Int) {
        for (a in attrs) {
            val g = LuaReg.objGet[a]
            LuaReg.objSet[a] = ObjSet { o, v ->
                val dst = g?.get(o)
                if (dst != null && v != null) LuaWritable.copyInto(dst, v, A.attrType(a), 0)
            }
        }
    }

    /** A read of the value [read] returns from a writable text member. */
    private fun text(a: Int, read: (Any?) -> GameText?, write: (Any?, GameText) -> Unit) {
        LuaReg.objGet[a] = ObjGet { o -> LuaViews.cached(o!!, a) { x -> LuaTextRef({ read(x) }, { g -> write(x, g) }) } }
        embedded(a)
    }

    /** A colour member over a definition's [HsColour]. */
    private fun colour(a: Int, get: (Any?) -> HsColour, set: (Any?, HsColour) -> Unit) {
        LuaReg.objGet[a] = ObjGet { o -> LuaViews.cached(o!!, a) { x -> LuaDefColor { get(x) } } }
        LuaReg.objSet[a] = ObjSet { o, v ->
            val from = v as? GlColorValue ?: return@ObjSet
            set(o, HsColour(from.r, from.g, from.b, from.a))
            (LuaReg.objGet[a]?.get(o) as? GlColorValue)?.let { it.r = from.r; it.g = from.g; it.b = from.b; it.a = from.a }
        }
    }

    /** A flag member over an engine field. */
    private fun flag(a: Int, set: (Any?, Boolean) -> Unit) { LuaReg.numSet[a] = NumSet { o, v -> set(o, v != 0.0) } }
    private fun int(a: Int, set: (Any?, Int) -> Unit) { LuaReg.numSet[a] = NumSet { o, v -> set(o, v.toInt()) } }

    // ---------------------------------------------------------------- TemporaryPowerDefinition
    private fun temporaryPower() {
        fun td(o: Any?) = o as TemporaryPowerDefinition
        // ToggleValue members and vectors: the object the crew code reads.
        embedded(A.A_TemporaryPowerDefinition_sounds, A.A_TemporaryPowerDefinition_maxHealth, A.A_TemporaryPowerDefinition_stunMultiplier,
            A.A_TemporaryPowerDefinition_moveSpeedMultiplier, A.A_TemporaryPowerDefinition_damageMultiplier, A.A_TemporaryPowerDefinition_rangedDamageMultiplier,
            A.A_TemporaryPowerDefinition_doorDamageMultiplier, A.A_TemporaryPowerDefinition_repairSpeed, A.A_TemporaryPowerDefinition_fireRepairMultiplier,
            A.A_TemporaryPowerDefinition_controllable, A.A_TemporaryPowerDefinition_canFight, A.A_TemporaryPowerDefinition_canRepair,
            A.A_TemporaryPowerDefinition_canSabotage, A.A_TemporaryPowerDefinition_canMan, A.A_TemporaryPowerDefinition_canTeleport,
            A.A_TemporaryPowerDefinition_canSuffocate, A.A_TemporaryPowerDefinition_canBurn, A.A_TemporaryPowerDefinition_oxygenChangeSpeed,
            A.A_TemporaryPowerDefinition_canPhaseThroughDoors, A.A_TemporaryPowerDefinition_fireDamageMultiplier, A.A_TemporaryPowerDefinition_persDamageMultiplier,
            A.A_TemporaryPowerDefinition_persHealMultiplier, A.A_TemporaryPowerDefinition_isTelepathic, A.A_TemporaryPowerDefinition_resistsMindControl,
            A.A_TemporaryPowerDefinition_isAnaerobic, A.A_TemporaryPowerDefinition_detectsLifeforms, A.A_TemporaryPowerDefinition_damageTakenMultiplier,
            A.A_TemporaryPowerDefinition_cloneSpeedMultiplier, A.A_TemporaryPowerDefinition_passiveHealAmount, A.A_TemporaryPowerDefinition_truePassiveHealAmount,
            A.A_TemporaryPowerDefinition_healAmount, A.A_TemporaryPowerDefinition_trueHealAmount, A.A_TemporaryPowerDefinition_passiveHealDelay,
            A.A_TemporaryPowerDefinition_sabotageSpeedMultiplier, A.A_TemporaryPowerDefinition_allDamageTakenMultiplier, A.A_TemporaryPowerDefinition_healSpeed,
            A.A_TemporaryPowerDefinition_suffocationModifier, A.A_TemporaryPowerDefinition_healCrewAmount, A.A_TemporaryPowerDefinition_powerDrain,
            A.A_TemporaryPowerDefinition_powerDrainFriendly, A.A_TemporaryPowerDefinition_bonusPower, A.A_TemporaryPowerDefinition_damageEnemiesAmount,
            A.A_TemporaryPowerDefinition_hackDoors, A.A_TemporaryPowerDefinition_powerRechargeMultiplier, A.A_TemporaryPowerDefinition_noClone,
            A.A_TemporaryPowerDefinition_noAI, A.A_TemporaryPowerDefinition_validTarget, A.A_TemporaryPowerDefinition_canMove,
            A.A_TemporaryPowerDefinition_teleportMove, A.A_TemporaryPowerDefinition_teleportMoveOtherShip, A.A_TemporaryPowerDefinition_silenced,
            A.A_TemporaryPowerDefinition_lowHealthThreshold, A.A_TemporaryPowerDefinition_statBoosts)
        colour(A.A_TemporaryPowerDefinition_cooldownColor, { td(it).cooldownColour }, { o, c -> td(o).cooldownColour = c })
    }

    // ---------------------------------------------------------------- ActivatedPowerRequirements
    private fun requirements() {
        fun rq(o: Any?) = o as ActivatedPowerRequirements
        int(A.A_ActivatedPowerRequirements_type) { o, v -> rq(o).type = ActivatedPowerRequirements.Type.values().getOrElse(v) { ActivatedPowerRequirements.Type.UNKNOWN } }
        flag(A.A_ActivatedPowerRequirements_playerShip) { o, v -> rq(o).playerShip = v }
        flag(A.A_ActivatedPowerRequirements_enemyShip) { o, v -> rq(o).enemyShip = v }
        flag(A.A_ActivatedPowerRequirements_checkRoomCrew) { o, v -> rq(o).checkRoomCrew = v }
        flag(A.A_ActivatedPowerRequirements_enemyInRoom) { o, v -> rq(o).enemyInRoom = v }
        flag(A.A_ActivatedPowerRequirements_friendlyInRoom) { o, v -> rq(o).friendlyInRoom = v }
        flag(A.A_ActivatedPowerRequirements_systemInRoom) { o, v -> rq(o).systemInRoom = v }
        flag(A.A_ActivatedPowerRequirements_systemDamaged) { o, v -> rq(o).systemDamaged = v }
        flag(A.A_ActivatedPowerRequirements_hasClonebay) { o, v -> rq(o).hasClonebay = v }
        flag(A.A_ActivatedPowerRequirements_aiDisabled) { o, v -> rq(o).aiDisabled = v }
        flag(A.A_ActivatedPowerRequirements_outOfCombat) { o, v -> rq(o).outOfCombat = v }
        flag(A.A_ActivatedPowerRequirements_inCombat) { o, v -> rq(o).inCombat = v }
        int(A.A_ActivatedPowerRequirements_requiredSystem) { o, v -> rq(o).requiredSystem = v }
        flag(A.A_ActivatedPowerRequirements_requiredSystemFunctional) { o, v -> rq(o).requiredSystemFunctional = v }
        embedded(A.A_ActivatedPowerRequirements_whiteList, A.A_ActivatedPowerRequirements_friendlyWhiteList, A.A_ActivatedPowerRequirements_friendlyBlackList,
            A.A_ActivatedPowerRequirements_enemyWhiteList, A.A_ActivatedPowerRequirements_enemyBlackList, A.A_ActivatedPowerRequirements_minHealth,
            A.A_ActivatedPowerRequirements_maxHealth, A.A_ActivatedPowerRequirements_extraConditions, A.A_ActivatedPowerRequirements_extraOrConditions,
            A.A_ActivatedPowerRequirements_extraOrConditionsTooltip)
    }

    // ---------------------------------------------------------------- ActivatedPowerDefinition, PowerResourceDefinition
    private fun activatedPower() {
        fun ad(o: Any?) = o as ActivatedPowerDefinition
        fun rd(o: Any?) = o as PowerResourceDefinition
        embedded(A.A_ActivatedPowerDefinition_damage, A.A_ActivatedPowerDefinition_sounds, A.A_ActivatedPowerDefinition_effectSounds,
            A.A_ActivatedPowerDefinition_buttonLabel, A.A_ActivatedPowerDefinition_tooltip, A.A_ActivatedPowerDefinition_playerReq,
            A.A_ActivatedPowerDefinition_enemyReq, A.A_ActivatedPowerDefinition_statBoosts, A.A_ActivatedPowerDefinition_roomStatBoosts,
            A.A_ActivatedPowerDefinition_tempPower)
        colour(A.A_ActivatedPowerDefinition_cooldownColor, { ad(it).cooldownColour }, { o, c -> ad(o).cooldownColour = c })
        colour(A.A_PowerResourceDefinition_cooldownColor, { rd(it).cooldownColour }, { o, c -> rd(o).cooldownColour = c })
        LuaReg.objSet[A.A_ActivatedPowerDefinition_chargeReq] = ObjSet { o, v -> ad(o).chargeReq = v as ActivatedPowerRequirements? }
        LuaReg.objSet[A.A_PowerResourceDefinition_chargeReq] = ObjSet { o, v -> rd(o).chargeReq = v as ActivatedPowerRequirements? }
        // Members a script holds only as an opaque value of another definition: `a.x = b.x` copies the vector.
        LuaReg.objSet[A.A_ActivatedPowerDefinition_powerResources] = ObjSet { o, v ->
            ((v as? LuaOpaqueMember)?.owner as? ActivatedPowerDefinition)?.let { ad(o).powerResources = ArrayList(it.powerResources) }
        }
        LuaReg.objSet[A.A_ActivatedPowerDefinition_crewSpawns] = ObjSet { o, v ->
            ((v as? LuaOpaqueMember)?.owner as? ActivatedPowerDefinition)?.let { ad(o).crewSpawns = ArrayList(it.crewSpawns) }
        }
        LuaReg.objSet[A.A_ActivatedPowerDefinition_event] = ObjSet { o, v ->
            ((v as? LuaOpaqueMember)?.owner as? ActivatedPowerDefinition)?.let { from ->
                ad(o).event[0] = from.event[0]; ad(o).event[1] = from.event[1]; ad(o).eventNodes = from.eventNodes.copyOf()
            }
        }
    }

    // ---------------------------------------------------------------- CrewDefinition
    private fun crewDefinition() {
        fun cd(o: Any?) = o as CrewDefinition
        embedded(A.A_CrewDefinition_deathSounds, A.A_CrewDefinition_deathSoundsFemale, A.A_CrewDefinition_shootingSounds, A.A_CrewDefinition_repairSounds,
            A.A_CrewDefinition_canPunch, A.A_CrewDefinition_shootTimer, A.A_CrewDefinition_punchTimer, A.A_CrewDefinition_powerDefs,
            A.A_CrewDefinition_passiveStatBoosts, A.A_CrewDefinition_nameRace, A.A_CrewDefinition_transformName)
        // Reachable only as another definition's opaque member: the copy of that member.
        fun from(v: Any?) = (v as? LuaOpaqueMember)?.owner as? CrewDefinition
        LuaReg.objSet[A.A_CrewDefinition_animSheet] = ObjSet { o, v -> from(v)?.let { cd(o).animSheet[0] = it.animSheet[0]; cd(o).animSheet[1] = it.animSheet[1] } }
        LuaReg.objSet[A.A_CrewDefinition_droneAI] = ObjSet { o, v -> from(v)?.let { cd(o).droneAI = it.droneAI } }
        LuaReg.objSet[A.A_CrewDefinition_explosionDef] = ObjSet { o, v -> from(v)?.let { cd(o).explosionDef = it.explosionDef } }
        LuaReg.objSet[A.A_CrewDefinition_skillsDef] = ObjSet { o, v -> from(v)?.let { cd(o).skillsDef = it.skillsDef } }
    }

    // ---------------------------------------------------------------- CustomShipDefinition and its entries
    private fun shipDefinition() {
        fun d(o: Any?) = o as CustomShipDefinition
        fun c(o: Any?) = o as CrewPlacement
        LuaReg.strSet[A.A_CustomShipDefinition_name] = StrSet { o, v -> d(o).name = v }
        flag(A.A_CustomShipDefinition_noJump) { o, v -> d(o).noJump = v }
        flag(A.A_CustomShipDefinition_noFuelStalemate) { o, v -> d(o).noFuelStalemate = v }
        int(A.A_CustomShipDefinition_hpCap) { o, v -> d(o).hpCap = v }
        int(A.A_CustomShipDefinition_startingFuel) { o, v -> d(o).startingFuel = v }
        int(A.A_CustomShipDefinition_startingScrap) { o, v -> d(o).startingScrap = v }
        int(A.A_CustomShipDefinition_systemLimit) { o, v -> d(o).systemLimit = v }
        int(A.A_CustomShipDefinition_subsystemLimit) { o, v -> d(o).subsystemLimit = v }
        int(A.A_CustomShipDefinition_reactorPriceIncrement) { o, v -> d(o).reactorPriceIncrement = v }
        int(A.A_CustomShipDefinition_maxReactorLevel) { o, v -> d(o).maxReactorLevel = v }
        LuaReg.strSet[A.A_CustomShipDefinition_shipGenerator] = StrSet { o, v -> d(o).shipGenerator = v }
        embedded(A.A_CustomShipDefinition_shipIcons, A.A_CustomShipDefinition_reactorPrices, A.A_CustomShipDefinition_crewList)
        LuaReg.objSet[A.A_CustomShipDefinition_hiddenAugs] = ObjSet { o, v ->
            (v as? LuaAdaptersWP2.StringMapView)?.let { d(o).hiddenAugs = java.util.TreeMap(it.map) }
        }
        // ToggleValue<bool> forceAutomated: the definition's optional flag behind a toggle that writes through.
        LuaReg.objGet[A.A_CustomShipDefinition_forceAutomated] = ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomShipDefinition_forceAutomated) { x ->
                val t = Toggle(d(x).forceAutomated ?: false).also { it.enabled = d(x).forceAutomated != null }
                t.onWrite = { d(x).forceAutomated = if (t.enabled) t.value else null }
                t
            }
        }
        embedded(A.A_CustomShipDefinition_forceAutomated)
        LuaReg.objSet[A.A_CustomShipDefinition_roomDefs] = ObjSet { o, v ->
            (v as? xyz.znix.xftl.hs.ship.HsShipsLua.RoomDefs)?.let { d(o).roomDefs = HashMap(it.def.roomDefs) }
        }
        LuaReg.strSet[A.A_CrewPlacementDefinition_species] = StrSet { o, v -> c(o).species = v }
        LuaReg.strSet[A.A_CrewPlacementDefinition_name] = StrSet { o, v -> c(o).name = v }
        int(A.A_CrewPlacementDefinition_roomId) { o, v -> c(o).roomId = v }
        flag(A.A_CrewPlacementDefinition_isList) { o, v -> c(o).isList = v }
        int(A.A_RoomDefinition_roomId) { o, v -> (o as RoomDefinition).roomId = v }
    }

    // ---------------------------------------------------------------- ShipBlueprint
    private fun shipBlueprint() {
        fun b(o: Any?) = o as ShipBlueprint
        int(A.A_ShipBlueprint_health) { o, v -> b(o).maxHealth = v }
        int(A.A_ShipBlueprint_maxPower) { o, v -> b(o).startingReactorPower = v }
        int(A.A_ShipBlueprint_missiles) { o, v -> b(o).initialMissiles = v }
        int(A.A_ShipBlueprint_drone_count) { o, v -> b(o).initialDroneParts = v }
        int(A.A_ShipBlueprint_weaponSlots) { o, v -> b(o).weaponSlots = v }
        int(A.A_ShipBlueprint_droneSlots) { o, v -> b(o).droneSlots = v }
        int(A.A_ShipBlueprint_boardingAI) { o, v ->
            b(o).boardingStrategy = when (v) {
                0 -> xyz.znix.xftl.ai.FriendlyCrewAI.BoardingStrategy.NONE
                1 -> xyz.znix.xftl.ai.FriendlyCrewAI.BoardingStrategy.SABOTAGE
                else -> xyz.znix.xftl.ai.FriendlyCrewAI.BoardingStrategy.INVASION
            }
        }
        text(A.A_ShipBlueprint_name, { xyz.znix.xftl.hs.render.HsSwitchShip.ownName(b(it)) ?: b(it).shipTitle }, { o, g -> b(o).shipTitle = g })
        text(A.A_ShipBlueprint_shipClass, { b(it).shipClass }, { o, g -> b(o).shipClass = g })
        text(A.A_ShipBlueprint_unlock, { b(it).unlockHint }, { o, g -> b(o).unlockHint = g })
        embedded(A.A_ShipBlueprint_desc)
        // Files: a new ship loads them, so the blueprint's parsed layout and images are rebuilt from the written names.
        fun file(a: Int, f: (ShipBlueprint, String) -> Unit) {
            LuaReg.strSet[a] = StrSet { o, v ->
                LuaStored.put(o, a, v)
                // A name that isn't a file is only a string until a ship is built from it.
                try { f(b(o), v) } catch (e: Exception) { }
            }
        }
        file(A.A_ShipBlueprint_layoutFile) { s, v -> s.hsSetFiles(layout = v) }
        file(A.A_ShipBlueprint_imgFile) { s, v -> s.hsSetFiles(img = v) }
        file(A.A_ShipBlueprint_cloakFile) { s, v -> s.hsSetFiles(cloak = v) }
        file(A.A_ShipBlueprint_shieldFile) { s, v -> s.hsSetFiles(shield = v) }
        file(A.A_ShipBlueprint_floorFile) { s, v -> s.hsSetFiles(floor = v) }
        // Lists a new ship is built from.
        fun strings(a: Int, read: (ShipBlueprint) -> List<String>, write: (ShipBlueprint, List<String>) -> Unit) {
            LuaReg.objGet[a] = ObjGet { o ->
                LuaViews.cached(o!!, a) { x -> ListView(A.attrType(a), 0, LuaTrackedList(read(b(x))) { l -> write(b(x), l.map { it.toString() }) }) }
            }
            embedded(a)
        }
        // The ship's own copy starts from the blueprint's name.
        LuaReg.strSet[A.A_ShipBlueprint_blueprintName] = StrSet { o, v -> LuaStored.put(o, A.A_ShipBlueprint_blueprintName, v); b(o).sourceElement.setAttribute("name", v) }
        // originalCrewCount: how many entries of the crew list a ship built later takes.
        int(A.A_ShipBlueprint_originalCrewCount) { o, v -> LuaStored.put(o, A.A_ShipBlueprint_originalCrewCount, v.toDouble()); b(o).hsCrewCount = v }
        int(A.A_ShipBlueprint_maxCrew) { o, v -> LuaStored.put(o, A.A_ShipBlueprint_maxCrew, v.toDouble()); b(o).hsMaxCrew = v }
        // The ids a hangar lists; a ship is built from systemInfo (oracle hw-bp4: the ship keeps all its systems).
        LuaReg.objGet[A.A_ShipBlueprint_systems]?.let { computed ->
            LuaReg.objGet[A.A_ShipBlueprint_systems] = ObjGet { o ->
                LuaViews.cached(o!!, -A.A_ShipBlueprint_systems) { x ->
                    val from = computed.get(x) as ListView
                    ListView(A.T_vector_int, 0, ArrayList<Any?>((0 until from.size()).map { from.get(it) }))
                }
            }
        }
        strings(A.A_ShipBlueprint_weapons, { it.initialWeapons }, { s, l -> s.initialWeapons = l })
        strings(A.A_ShipBlueprint_drones, { it.initialDrones }, { s, l -> s.initialDrones = l })
        strings(A.A_ShipBlueprint_augments, { it.initialAugments }, { s, l -> s.initialAugments = l; s.hangarAugments = l.sorted() })
        // weaponCount, droneCount: how many entries of the lists a ship takes.
        int(A.A_ShipBlueprint_droneCount) { o, v -> LuaStored.put(o, A.A_ShipBlueprint_droneCount, v.toDouble()); b(o).droneListCount = v }
        int(A.A_ShipBlueprint_weaponCount) { o, v -> LuaStored.put(o, A.A_ShipBlueprint_weaponCount, v.toDouble()); b(o).weaponListCount = v }
    }

    // ---------------------------------------------------------------- WeaponBlueprint
    private fun weaponBlueprint() {
        fun b(o: Any?) = o as xyz.znix.xftl.weapons.AbstractWeaponBlueprint
        embedded(A.A_WeaponBlueprint_damage, A.A_WeaponBlueprint_effects, A.A_WeaponBlueprint_boostPower, A.A_WeaponBlueprint_flavorType)
        LuaReg.strSet[A.A_WeaponBlueprint_weaponArt] = StrSet { o, v -> b(o).hsLuaFacts?.weaponArt = v; b(o).launcher = v }
        LuaReg.strSet[A.A_WeaponBlueprint_combatIcon] = StrSet { o, v -> b(o).hsLuaFacts?.combatIcon = v }
        LuaReg.strSet[A.A_WeaponBlueprint_explosion] = StrSet { o, v -> b(o).hsLuaFacts?.explosion = v; b(o).hsExplosion = v }
        LuaReg.numSet[A.A_WeaponBlueprint_spin] = NumSet { o, v -> b(o).hsLuaFacts?.spin = v; b(o).spin = v.toInt() }
        LuaReg.numSet[A.A_WeaponBlueprint_drone_targetable] = NumSet { o, v -> b(o).hsLuaFacts?.droneTargetable = v; b(o).droneTargetable = v.toInt() }
        LuaReg.numSet[A.A_WeaponBlueprint_miniCount] = NumSet { o, v -> b(o).hsLuaFacts?.miniCount = v.toInt() }
    }
}
