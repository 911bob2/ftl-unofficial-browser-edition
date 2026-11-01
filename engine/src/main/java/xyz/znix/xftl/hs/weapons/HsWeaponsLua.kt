// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDamage.cpp,
// src/game/Gameplay/CustomWeapons.cpp, lua/InternalEvents.cpp (event arguments and results)
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.weapons.*
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * The Lua side of the weapon family: adapters for Damage, Projectile (+ Projectile_Extend, CustomDamage),
 * ProjectileFactory, WeaponBlueprint and ShipManager::DamageArea, and the DAMAGE_* / PROJECTILE_* / SHIELD_COLLISION
 * / WEAPON_* / DRONE_FIRE / CONSTRUCT_PROJECTILE_FACTORY events at HS's hook positions. Every event entry point
 * returns at once unless [HS.luaActive] and a callback is registered.
 *
 * Engine mapping (HS field -> engine):
 * - Damage: iDamage hullDamage; iIonDamage ionDamage; iSystemDamage pureSysDamage; iPersDamage pureCrewDamage / 15;
 *   fire/breach/stunChance the engine's percent / 10; bHullBuster emptyRoomBonus != 0; iStun stunDuration;
 *   ownerId the owner ship's id; iShieldPiercing, selfId, crystalShard are kept on the Damage (hs* fields).
 * - ProjectileFactory: cooldown = (timeCharged, chargeTime); chargeLevel = ready charges (extra + current);
 *   powered; iShipId; numShots = blueprint shots; shotsFiredAtTarget = HS's counter.
 */
object HsWeaponsLua {
    /** CustomDamage view of an extend (a member struct of Projectile_Extend). */
    class CustomDamageView(@JvmField val ext: HsProjectileExt) : LuaTyped { override val luaType get() = A.T_CustomDamage }

    /** Projectile_Extend as Lua sees it. */
    class ExtendView(@JvmField val ext: HsProjectileExt) : LuaTyped {
        override val luaType get() = A.T_Projectile_Extend
        @JvmField val customDamage = CustomDamageView(ext)
    }

    internal fun extOf(o: Any?): HsProjectileExt? = when (o) {
        is IProjectile -> o.hsExt
        else -> null
    }

    private fun extView(ext: HsProjectileExt?): ExtendView? {
        if (ext == null) return null
        return (ext.luaView as? ExtendView) ?: ExtendView(ext).also { ext.luaView = it }
    }

    /** The ship a projectile lives in now (Projectile::currentSpace). */
    private fun currentSpace(p: IProjectile): Ship? {
        val s = LuaAdapters.inGame() ?: return null
        return when {
            s.player.projectiles.contains(p) -> s.player
            s.enemy?.projectiles?.contains(p) == true -> s.enemy
            else -> null
        }
    }

    private fun ownerShip(p: Any?): Ship? = when (p) {
        is AbstractWeaponProjectile -> p.hsOwner ?: p.ship.sys.getEnemyOf(p.ship)
        else -> null
    }

    private fun targetShip(p: Any?): Ship? = when (p) {
        is AbstractProjectile -> p.targetShip
        else -> null
    }

    private val tmpPointf = PointfValue(0f, 0f)

    @JvmStatic
    fun install() {
        damage()
        projectile()
        factory()
        blueprint()
        LuaReg.method(A.M_ShipManager_DamageArea, LuaMethod {
            val ship = arg.obj(1) as Ship
            val p = arg.obj(2) as? PointfValue
            val d = arg.obj(3) as? Damage ?: Damage()
            ret.bool(if (p == null) false else scriptDamageArea(ship, ConstPoint(p.x.toInt(), p.y.toInt()), d, arg.bool(4)))
        })
    }

    private fun damage() {
        LuaReg.method(A.M_new_Damage, LuaMethod { ret.obj(Damage(), A.T_Damage) })
        fun d(o: Any?) = o as Damage
        LuaReg.num(A.A_Damage_iDamage, NumGet { d(it).hullDamage.toDouble() }, NumSet { o, v -> d(o).hullDamage = v.toInt() })
        LuaReg.num(A.A_Damage_iShieldPiercing, NumGet { d(it).hsShieldPiercing.toDouble() }, NumSet { o, v -> d(o).hsShieldPiercing = v.toInt() })
        LuaReg.num(A.A_Damage_fireChance, NumGet { (d(it).fireChance / 10).toDouble() }, NumSet { o, v -> d(o).fireChance = v.toInt() * 10 })
        LuaReg.num(A.A_Damage_breachChance, NumGet { (d(it).breachChance / 10).toDouble() }, NumSet { o, v -> d(o).breachChance = v.toInt() * 10 })
        LuaReg.num(A.A_Damage_stunChance, NumGet { (d(it).stunChance / 10).toDouble() }, NumSet { o, v -> d(o).stunChance = v.toInt() * 10 })
        LuaReg.num(A.A_Damage_iIonDamage, NumGet { d(it).ionDamage.toDouble() }, NumSet { o, v -> d(o).ionDamage = v.toInt() })
        LuaReg.num(A.A_Damage_iSystemDamage, NumGet { d(it).pureSysDamage.toDouble() }, NumSet { o, v -> d(o).pureSysDamage = v.toInt() })
        LuaReg.num(A.A_Damage_iPersDamage, NumGet { (d(it).pureCrewDamage / 15).toDouble() }, NumSet { o, v -> d(o).pureCrewDamage = v.toInt() * 15 })
        LuaReg.bool(A.A_Damage_bHullBuster, { d(it).emptyRoomBonus != 0 }, { o, b -> d(o).emptyRoomBonus = if (b) 1 else 0 })
        LuaReg.num(A.A_Damage_ownerId, NumGet { d(it).hsOwnerId.toDouble() }, NumSet { o, v -> d(o).hsOwnerId = v.toInt(); d(o).ownerShip = LuaAdapters.shipManager(v.toInt()) })
        LuaReg.num(A.A_Damage_selfId, NumGet { d(it).hsSelfId.toDouble() }, NumSet { o, v -> d(o).hsSelfId = v.toInt() })
        LuaReg.bool(A.A_Damage_bLockdown, { d(it).lockdown }, { o, b -> d(o).lockdown = b })
        LuaReg.bool(A.A_Damage_crystalShard, { d(it).hsCrystalShard }, { o, b -> d(o).hsCrystalShard = b })
        LuaReg.bool(A.A_Damage_bFriendlyFire, { d(it).hsFriendlyFire }, { o, b -> d(o).hsFriendlyFire = b; d(o).friendlyFire = b })
        LuaReg.num(A.A_Damage_iStun, NumGet { d(it).stunDuration.toDouble() }, NumSet { o, v -> d(o).stunDuration = v.toInt().toFloat() })
    }

    private fun projectile() {
        LuaReg.obj(A.A_Projectile_extend, ObjGet { extView(extOf(it)) })
        LuaReg.str(A.A_Projectile_Extend_name, StrGet { (it as ExtendView).ext.name }, StrSet { o, v -> (o as ExtendView).ext.name = v })
        LuaReg.obj(A.A_Projectile_Extend_customDamage, ObjGet { (it as ExtendView).customDamage })
        LuaReg.num(A.A_CustomDamage_accuracyMod, NumGet { (it as CustomDamageView).ext.accuracyMod.toDouble() },
            NumSet { o, v -> (o as CustomDamageView).ext.accuracyMod = v.toInt() })
        LuaReg.num(A.A_CustomDamage_droneAccuracyMod, NumGet { (it as CustomDamageView).ext.droneAccuracyMod.toDouble() },
            NumSet { o, v -> (o as CustomDamageView).ext.droneAccuracyMod = v.toInt() })
        LuaReg.num(A.A_CustomDamage_sourceShipId, NumGet { (it as CustomDamageView).ext.sourceShipId.toDouble() },
            NumSet { o, v -> (o as CustomDamageView).ext.sourceShipId = v.toInt() })

        LuaReg.num(A.A_Projectile_ownerId, NumGet { HsWeapons.shipId(ownerShip(it)).toDouble() })
        LuaReg.num(A.A_Projectile_destinationSpace, NumGet { HsWeapons.shipId(targetShip(it)).toDouble() }, NumSet { o, v -> retargetShip(o, v.toInt()) })
        LuaReg.num(A.A_Projectile_targetId, NumGet { HsWeapons.shipId(targetShip(it)).toDouble() }, NumSet { o, v -> retargetShip(o, v.toInt()) })
        LuaReg.num(A.A_Projectile_currentSpace, NumGet {
            when (it) {
                is IProjectile -> HsWeapons.shipId(currentSpace(it)).toDouble()
                else -> -1.0
            }
        })
        LuaReg.num(A.A_Projectile_heading, NumGet {
            if (it is AbstractProjectile) Math.toDegrees(it.rotation.toDouble()) else 0.0
        })
        LuaReg.obj(A.A_Projectile_position, ObjGet {
            val p = when (it) {
                is IProjectile -> it.position
                else -> null
            }
            if (p == null) null else PointfValue(p.xf, p.yf)
        })
        LuaReg.obj(A.A_Projectile_target, ObjGet {
            when (it) {
                is AbstractWeaponProjectile -> it.calculateTargetPosition().let { q -> PointfValue(q.x.toFloat(), q.y.toFloat()) }
                is AbstractProjectile -> PointfValue(it.targetPos.x.toFloat(), it.targetPos.y.toFloat())
                else -> null
            }
        }, ObjSet { o, v ->
            val p = v as? PointfValue ?: return@ObjSet
            retarget(o, ConstPoint(p.x.toInt(), p.y.toInt()))
        })
        LuaReg.obj(A.A_Projectile_color, ObjGet { null })
        // ComputeHeading: the engine aims every frame from the current target, nothing to recompute
        LuaReg.method(A.M_Projectile_ComputeHeading, LuaMethod { 0 })
        LuaReg.method(A.M_Projectile_Kill, LuaMethod {
            when (val p = arg.obj(1)) {
                is AbstractProjectile -> p.hsKill()
                else -> HS.unsupported("lua", "method", "Projectile.Kill (${p?.javaClass?.simpleName})")
            }
            0
        })
        LuaReg.method(A.M_Projectile_GetType, LuaMethod {
            ret.num(when (arg.obj(1)) {
                is LaserBlueprint.LaserProjectile -> 1; is MissileBody -> 3
                is BombProjectile -> 4; is BeamProjectile -> 5; else -> 1
            })
        })
    }

    /** `projectile.target = point`: aim at the room of the target ship containing the point (ship space). */
    private fun retarget(o: Any?, point: IPoint) {
        val p = o as? AbstractWeaponProjectile ?: return HS.unsupported("lua", "attribute", "Projectile.target (${o?.javaClass?.simpleName})")
        val room = p.ship.rooms.firstOrNull { it.containsShipSpace(point) }
            ?: return HS.unsupported("lua", "attribute", "Projectile.target outside a room")
        p.target = room
        if (p.hsInTargetSpace) p.targetPos = p.calculateTargetPosition()
    }

    /**
     * `projectile.destinationSpace = id` / `targetId = id` (MV's mv-aiSystemTarget, in PROJECTILE_FIRE): aim a
     * projectile that has not reached its target space at ship [id]. The engine keeps one target ship for both
     * fields; the room stays the one with the same id (or the first room) until the script sets `target`.
     */
    private fun retargetShip(o: Any?, id: Int) {
        if (HsWeapons.shipId(targetShip(o)) == id) return
        val p = o as? AbstractWeaponProjectile
        val ship = LuaAdapters.shipManager(id)
        if (p == null || ship == null || p.hsInTargetSpace)
            return HS.unsupported("lua", "attribute", "Projectile.destinationSpace/targetId (${o?.javaClass?.simpleName}, ship $id)")
        val room = ship.rooms.firstOrNull { it.id == p.target.id } ?: ship.rooms.firstOrNull()
            ?: return HS.unsupported("lua", "attribute", "Projectile.destinationSpace/targetId: ship without rooms")
        if (p.hsOwner == null) p.hsOwner = ownerShip(p)
        p.target = room
        p.hsSetTargetShip(ship, currentSpace(p))
    }

    private fun factory() {
        fun w(o: Any?) = o as AbstractWeaponInstance
        LuaReg.obj(A.A_ProjectileFactory_cooldown, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ProjectileFactory_cooldown) { x ->
                val wi = x as AbstractWeaponInstance
                // SetCooldownModifier scales the pair and keeps the fraction; the engine scales the charge rate instead.
                fun mod() = wi.hsCooldownMod.coerceAtLeast(1e-4f)
                FloatPairView(A.T_pair_float_float, FGet { wi.timeCharged * mod() },
                    FSet { wi.hsWriteCharge(if (it >= wi.chargeTime * mod()) wi.chargeTime else it / mod()) },
                    FGet { wi.chargeTime * mod() }, null)
            }
        })
        LuaReg.obj(A.A_ProjectileFactory_subCooldown, ObjGet { o ->
            // ProjectileFactory::subCooldown: HS's Update (CustomWeapons.cpp, 1b05a07) only reads it as a charge gate
            // and moves .first towards .second; nothing but saves and Lua write it, so it stays (0, 0) unless a
            // script sets it.
            LuaViews.cached(o!!, A.A_ProjectileFactory_subCooldown) { x ->
                val w = x as AbstractWeaponInstance
                FloatPairView(A.T_pair_float_float, FGet { w.hsSubCooldownFirst }, FSet { w.hsSubCooldownFirst = it }, FGet { w.hsSubCooldownSecond }, FSet { w.hsSubCooldownSecond = it })
            }
        })
        LuaReg.num(A.A_ProjectileFactory_chargeLevel, NumGet { w(it).totalReadyCharges.toDouble() }, NumSet { o, v ->
            val wi = w(o)
            val n = v.toInt().coerceIn(0, wi.maxTotalCharges)
            wi.extraCharges = if (wi.isCurrentCharged) (n - 1).coerceAtLeast(0) else n.coerceAtMost(wi.maxExtraCharges)
        })
        LuaReg.bool(A.A_ProjectileFactory_powered, { w(it).isPowered })
        // ProjectileFactory is a ShipObject: iShipId is the ShipObject attribute, registered for ships by the
        // core adapters; weapons answer it here and everything else goes to that getter.
        val shipObjectId = LuaReg.numGet[A.A_ShipObject_iShipId]
        LuaReg.num(A.A_ShipObject_iShipId, NumGet { o ->
            if (o is AbstractWeaponInstance) HsWeapons.shipId(o.ship).toDouble()
            else shipObjectId?.get(o) ?: throw LuaError("iShipId")
        }, LuaReg.numSet[A.A_ShipObject_iShipId])
        LuaReg.num(A.A_ProjectileFactory_numShots, NumGet { w(it).type.shots.toDouble() })
        LuaReg.num(A.A_ProjectileFactory_shotsFiredAtTarget, NumGet { w(it).hsShotsFired.toDouble() }, NumSet { o, v -> w(o).hsShotsFired = v.toInt() })
        LuaReg.num(A.A_ProjectileFactory_requiredPower, NumGet { w(it).type.power.toDouble() })
        // autoFiring: the engine's autofire state until a script writes the field; a written value stays readable and
        // changes nothing else (ProjectileFactory::Fire leaves it alone, and a shot still untargets the weapon: oracle
        // hs-gaps aim4 / aim5)
        LuaReg.bool(A.A_ProjectileFactory_autoFiring, { val wi = w(it); wi.hsAutoFiringWritten ?: wi.autofireOverride ?: (wi.ship.weapons?.autofire == true) },
            { o, v -> w(o).hsAutoFiringWritten = v })
        // ProjectileFactory::queuedProjectiles / GetProjectile: the launcher's constructed volley (weapons/HsProjectileQueue).
        LuaReg.obj(A.A_ProjectileFactory_queuedProjectiles, ObjGet { o ->
            @Suppress("UNCHECKED_CAST")
            val queue = ((o as? xyz.znix.xftl.weapons.HsProjectileQueue)?.hsQueue ?: ArrayList()) as MutableList<Any?>
            ListView(A.T_vector_Projectile, A.T_Projectile, queue)
        })
        LuaReg.method(A.M_ProjectileFactory_GetProjectile, LuaMethod {
            val projectile = (arg.obj(1) as? xyz.znix.xftl.weapons.HsProjectileQueue)?.hsGetProjectile()
            if (projectile == null) ret.nil() else ret.obj(projectile, A.T_Projectile)
        })
        // Drone::deployed: the drone object is still the deployed instance of its system slot. Drone::selfId.
        LuaReg.bool(A.A_Drone_deployed, { d -> val dr = d as xyz.znix.xftl.drones.AbstractDrone; dr.ownerShip.drones?.drones?.any { it?.instance === dr } == true })
        LuaReg.num(A.A_Drone_selfId, NumGet { d -> val dr = d as xyz.znix.xftl.drones.AbstractDrone; if (dr.hsSelfId < 0) dr.hsSelfId = HsWeapons.nextSpaceId(); dr.hsSelfId.toDouble() })
        LuaReg.obj(A.A_ProjectileFactory_weaponVisual, ObjGet { o -> LuaViews.cached(o!!, A.A_ProjectileFactory_weaponVisual) { WeaponVisual(it as AbstractWeaponInstance) } })
        fun v(o: Any?) = o as WeaponVisual
        LuaReg.num(A.A_WeaponAnimation_iChargeLevels, NumGet { (v(it).w.type.chargeLevels ?: 0).toDouble() })
        LuaReg.num(A.A_WeaponAnimation_boostLevel, NumGet { v(it).boostLevel().toDouble() }, NumSet { _, _ -> })
        LuaReg.obj(A.A_WeaponAnimation_boostAnim, ObjGet { o -> v(o).anim })
        LuaReg.method(A.M_ProjectileFactory_ForceCoolup, LuaMethod {
            val wi = w(arg.obj(1))
            if (!(HS.active && HsWeapons.forceCoolup(wi)) && wi.isPowered) wi.timeCharged = wi.chargeTime
            0
        })
    }

    /**
     * ProjectileFactory::weaponVisual (WeaponAnimation). boostLevel is the charge level a charger's boost lights show
     * (charges stored minus one; while a volley fires, its charges minus one) or a chain weapon's chain count;
     * writes are ignored (the engine draws the lights from its own charge state). boostAnim is the launcher's
     * `<boost>` animation, as an Animation struct.
     */
    class WeaponVisual(@JvmField val w: AbstractWeaponInstance) : LuaTyped {
        override val luaType: Int get() = A.T_WeaponAnimation
        val anim: Any by lazy { w.animation.boostAnim?.let { LuaAnim.create(it) } ?: LuaStructs.create(A.T_Animation) }

        fun boostLevel(): Int {
            val firing = (w as? AbstractProjectileWeaponInstance)?.hsVolleyCharges ?: 0
            return when {
                firing > 0 -> firing - 1
                w.maxTotalCharges > 1 -> maxOf(0, w.totalReadyCharges - 1)
                else -> w.chainCount
            }
        }
    }

    private fun blueprint() {
        fun b(o: Any?) = o as AbstractWeaponBlueprint
        LuaReg.num(A.A_WeaponBlueprint_power, NumGet { b(it).power.toDouble() })
        LuaReg.str(A.A_WeaponBlueprint_typeName, StrGet {
            when (it) { is LaserBlueprint -> "LASER"; is MissileBlueprint -> "MISSILES"; is BeamBlueprint -> "BEAM"
                is BombBlueprint -> "BOMB"; is FlakBlueprint -> "BURST"; else -> "" }
        })
        LuaReg.num(A.A_WeaponBlueprint_speed, NumGet { (b(it).speed ?: 0).toDouble() })
        LuaReg.num(A.A_WeaponBlueprint_length, NumGet { ((it as? BeamBlueprint)?.length ?: 0).toDouble() })
        LuaReg.num(A.A_WeaponBlueprint_chargeLevels, NumGet { (b(it).chargeLevels ?: 0).toDouble() })
        LuaReg.num(A.A_WeaponBlueprint_shots, NumGet { b(it).shots.toDouble() })
        LuaReg.num(A.A_WeaponBlueprint_missiles, NumGet { b(it).missilesUsed.toDouble() })
        LuaReg.num(A.A_WeaponBlueprint_cooldown, NumGet { b(it).chargeTime.toDouble() })
        LuaReg.num(A.A_WeaponBlueprint_radius, NumGet { ((it as? FlakBlueprint)?.radius ?: 0).toDouble() })
        LuaReg.obj(A.A_WeaponBlueprint_damage, ObjGet { o -> LuaViews.cached(o!!, A.A_WeaponBlueprint_damage) { x -> Damage(x as AbstractWeaponBlueprint) } })
        LuaReg.obj(A.A_WeaponBlueprint_miniProjectiles, ObjGet { o ->
            LuaViews.cached(o!!, A.A_WeaponBlueprint_miniProjectiles) { x ->
                val list = (x as? FlakBlueprint)?.miniProjectiles?.map { (img, fake) ->
                    LuaStructs.create(A.T_MiniProjectile).also { s -> s.values[A.A_MiniProjectile_image] = img; s.values[A.A_MiniProjectile_fake] = fake }
                } ?: emptyList()
                ListView(A.T_vector_MiniProjectile, A.T_MiniProjectile) { list }
            }
        })
    }

    // ------------------------------------------------------------------ events

    private fun pushPoint(p: IPoint) {
        ret.obj(if (p is xyz.znix.xftl.math.FPoint) PointfValue(p.xf, p.yf) else PointfValue(p.x.toFloat(), p.y.toFloat()), A.T_Pointf)
    }

    /** Flag of [damageAreaEvent]'s result: a callback pre-empted DAMAGE_AREA. */
    const val PREEMPTED = 4

    /**
     * DAMAGE_AREA(ship, projectile, location, damage, forceHit ? HIT : NONE, shipFriendlyFire) before
     * ShipManager::DamageArea: the Evasion a callback returned (0 NONE, 1 HIT, 2 MISS), or-ed with [PREEMPTED].
     */
    @JvmStatic
    fun damageAreaEvent(ship: Ship, proj: Any?, location: IPoint, damage: Damage, forceHit: Boolean): Int {
        val initial = if (forceHit) 1 else 0
        if (!HsLua.hasInt[A.EV_DAMAGE_AREA]) return initial
        ret.obj(ship, A.T_ShipManager)
        ret.obj(proj, A.T_Projectile)
        pushPoint(location)
        ret.obj(damage, A.T_Damage)
        LuaJs.pushinteger(initial.toDouble())
        LuaJs.pushboolean(true)
        val preempt = LuaJs.fireChain(A.EV_DAMAGE_AREA, 6, 2)
        val force = if (LuaJs.isinteger(-2)) LuaJs.tointeger(-2).toInt() else 0
        LuaJs.pop(6)
        return (force and 3) or (if (preempt) PREEMPTED else 0)
    }

    /** ShipManager::DamageArea after a hit: DAMAGE_AREA_HIT(ship, projectile, location, damage, shipFriendlyFire);
     * true when a callback pre-empted (HS then reports a miss). */
    @JvmStatic
    fun damageAreaHit(ship: Ship, proj: Any?, location: IPoint, damage: Damage): Boolean {
        if (!HsLua.hasInt[A.EV_DAMAGE_AREA_HIT]) return false
        ret.obj(ship, A.T_ShipManager)
        ret.obj(proj, A.T_Projectile)
        pushPoint(location)
        ret.obj(damage, A.T_Damage)
        LuaJs.pushboolean(true)
        val preempt = LuaJs.fireChain(A.EV_DAMAGE_AREA_HIT, 5, 0)
        LuaJs.pop(5)
        return preempt
    }

    /**
     * ShipManager::DamageBeam, before super: DAMAGE_BEAM(ship, projectile, location, damage, realNewTile,
     * beamHitType). Returns the beam hit type to use (0 SAME_TILE, 1 NEW_TILE, 2 NEW_ROOM), or -1-type when a
     * callback pre-empted (HS skips the original then).
     */
    @JvmStatic
    fun damageBeam(ship: Ship, proj: Any?, location: IPoint, damage: Damage, realNewTile: Boolean, hitType: Int): Int {
        if (!HsLua.hasInt[A.EV_DAMAGE_BEAM]) return hitType
        ret.obj(ship, A.T_ShipManager)
        ret.obj(proj, A.T_Projectile)
        val point = if (location is xyz.znix.xftl.math.FPoint) PointfValue(location.xf, location.yf)
            else PointfValue(location.x.toFloat(), location.y.toFloat())
        ret.obj(point, A.T_Pointf)
        ret.obj(damage, A.T_Damage)
        LuaJs.pushboolean(realNewTile)
        LuaJs.pushinteger(hitType.toDouble())
        val preempt = LuaJs.fireChain(A.EV_DAMAGE_BEAM, 6, 1)
        val t = if (LuaJs.isinteger(-1)) LuaJs.tointeger(-1).toInt() else hitType
        LuaJs.pop(6)
        if (location is xyz.znix.xftl.math.MutFPoint) location.set(point.x, point.y)
        val type = if (t in 0..2) t else hitType
        return if (preempt) -1 - type else type
    }

    /** ShipManager::CollisionShield after a shield hit (collision type 2): SHIELD_COLLISION(ship, proj, damage, response). */
    @JvmStatic
    fun shieldCollision(ship: Ship, proj: Any?, damage: Damage, point: IPoint) {
        if (!HsLua.hasInt[A.EV_SHIELD_COLLISION]) return
        val response = LuaStructs.create(A.T_CollisionResponse)
        response.values[A.A_CollisionResponse_collision_type] = 2.0
        response.values[A.A_CollisionResponse_point] = PointfValue(point.x.toFloat(), point.y.toFloat())
        ret.obj(ship, A.T_ShipManager)
        ret.obj(proj, A.T_Projectile)
        ret.obj(damage, A.T_Damage)
        ret.obj(response, A.T_CollisionResponse)
        LuaJs.fireChain(A.EV_SHIELD_COLLISION, 4, 0)
        LuaJs.pop(4)
    }

    /** ProjectileFactory::GetProjectile (priority -1000), after a projectile left: PROJECTILE_FIRE(projectile, weapon). */
    @JvmStatic
    fun projectileFire(proj: Any, weapon: AbstractWeaponInstance) {
        if (!HsLua.hasInt[A.EV_PROJECTILE_FIRE]) return
        ret.obj(proj, A.T_Projectile)
        ret.obj(weapon, A.T_ProjectileFactory)
        LuaJs.fireChain(A.EV_PROJECTILE_FIRE, 2, 0)
        LuaJs.pop(2)
    }

    /** SpaceDrone::GetNextProjectile (priority -100): DRONE_FIRE(projectile, drone); true = pre-empted (no shot). */
    @JvmStatic
    fun droneFire(proj: Any, drone: Any): Boolean {
        if (!HsLua.hasInt[A.EV_DRONE_FIRE]) return false
        ret.obj(proj, A.T_Projectile)
        ret.obj(drone, A.T_SpaceDrone)
        val preempt = LuaJs.fireChain(A.EV_DRONE_FIRE, 2, 0)
        LuaJs.pop(2)
        return preempt
    }

    /** SpaceManager::UpdateProjectile, before the update: PROJECTILE_PRE(projectile); true = skip the update. */
    @JvmStatic
    fun projectilePre(proj: Any): Boolean {
        if (!HsLua.hasInt[A.EV_PROJECTILE_PRE]) return false
        ret.obj(proj, A.T_Projectile)
        val preempt = LuaJs.fireChain(A.EV_PROJECTILE_PRE, 1, 0)
        LuaJs.pop(1)
        return preempt
    }

    /** ProjectileFactory constructor, after super: CONSTRUCT_PROJECTILE_FACTORY(weapon). */
    @JvmStatic
    fun constructFactory(weapon: AbstractWeaponInstance) {
        if (!HsLua.hasInt[A.EV_CONSTRUCT_PROJECTILE_FACTORY]) return
        ret.obj(weapon, A.T_ProjectileFactory)
        LuaJs.fire(A.EV_CONSTRUCT_PROJECTILE_FACTORY, 1, 0)
        LuaJs.pop(1)
    }

    /**
     * ProjectileFactory::SetCooldownModifier: WEAPON_COOLDOWN_MOD(weapon, mod, artillery) may replace the modifier
     * (max 0, and at most 1 outside the artillery loop). Returns the modifier to set, or null when a callback
     * pre-empted (the original is skipped, so the weapon keeps its previous modifier).
     */
    @JvmStatic
    fun cooldownMod(weapon: AbstractWeaponInstance, mod: Float, artillery: Boolean): Float? {
        if (!HsLua.hasInt[A.EV_WEAPON_COOLDOWN_MOD]) return mod
        ret.obj(weapon, A.T_ProjectileFactory)
        LuaJs.pushnumber(mod.toDouble())
        LuaJs.pushboolean(artillery)
        val preempt = LuaJs.fireChain(A.EV_WEAPON_COOLDOWN_MOD, 3, 1)
        var m: Float? = mod
        if (LuaJs.isnumber(-1)) {
            var v = kotlin.math.max(0f, LuaJs.tonumber(-1).toFloat())
            if (!artillery) v = kotlin.math.min(v, 1f)
            m = v
        }
        LuaJs.pop(3)
        return if (preempt) null else m
    }

    /**
     * WeaponBox::RenderBox (CooldownNumbers.cpp): WEAPON_RENDERBOX(weapon, cooldown, maxCooldown, first, second,
     * third) may rewrite the three lines; false = pre-empted (nothing printed).
     */
    @JvmStatic
    fun renderBox(weapon: AbstractWeaponInstance, lines: HsCooldownNumbers.Lines): Boolean {
        if (!HsLua.hasInt[A.EV_WEAPON_RENDERBOX]) return true
        val (charged, total) = HsCooldownNumbers.cooldown(weapon)
        ret.obj(weapon, A.T_ProjectileFactory)
        LuaJs.pushnumber(charged.toDouble())
        LuaJs.pushnumber(total.toDouble())
        LuaJs.pushstring(lines.first)
        LuaJs.pushstring(lines.second)
        LuaJs.pushstring(lines.third)
        val preempt = LuaJs.fireChain(A.EV_WEAPON_RENDERBOX, 6, 3)
        if (LuaJs.isstring(-3)) lines.first = LuaJs.tostring(-3) ?: lines.first
        if (LuaJs.isstring(-2)) lines.second = LuaJs.tostring(-2) ?: lines.second
        if (LuaJs.isstring(-1)) lines.third = LuaJs.tostring(-1) ?: lines.third
        LuaJs.pop(6)
        return !preempt
    }

    /** The redesigned weapon stat box (RedesignedTooltips.cpp): WEAPON_STATBOX(bp, text); "" when pre-empted. */
    @JvmStatic
    fun statBox(bp: AbstractWeaponBlueprint, text: String): String {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_WEAPON_STATBOX]) return text
        ret.obj(bp, A.T_WeaponBlueprint)
        LuaJs.pushstring(text)
        val preempt = LuaJs.fireChain(A.EV_WEAPON_STATBOX, 2, 1)
        val s = if (LuaJs.isstring(-1)) LuaJs.tostring(-1) else text
        LuaJs.pop(2)
        return if (preempt) "" else s ?: text
    }

    /**
     * ShipManager::DamageSystem (priority -100): DAMAGE_SYSTEM(ship, projectile, roomId, damage) with the current
     * projectile; true = pre-empted (the system takes no damage). Ported from FTL-Hyperspace (GPL-3.0-or-later)
     * 1b05a07 src/game/Gameplay/CustomDamage.cpp. The engine resolves DamageArea in one function (Ship.damage), so
     * this fires where the system damage is applied, for every hit on a room with a system.
     */
    @JvmStatic
    fun damageSystem(ship: Ship, roomId: Int, damage: Damage): Boolean {
        if (!HsLua.hasInt[A.EV_DAMAGE_SYSTEM]) return false
        ret.obj(ship, A.T_ShipManager)
        ret.obj(HsDamage.currentProjectile, A.T_Projectile)
        LuaJs.pushinteger(roomId.toDouble())
        ret.obj(damage, A.T_Damage)
        val preempt = LuaJs.fireChain(A.EV_DAMAGE_SYSTEM, 4, 0)
        LuaJs.pop(4)
        return preempt
    }

    // ------------------------------------------------------------------ script damage

    /**
     * `ShipManager:DamageArea(location, damage, forceHit)` from a script: the room at [location] (ship space) takes
     * [damage]; without [forceHit] the ship's dodge is rolled first. Fires DAMAGE_AREA / DAMAGE_AREA_HIT with the
     * current projectile, as HS's hook does for every call.
     */
    @JvmStatic
    fun scriptDamageArea(ship: Ship, location: IPoint, damage: Damage, forceHit: Boolean): Boolean {
        // The same order as a projectile's hit (AbstractWeaponProjectile.hsDamageArea, native 0x4a6100).
        val proj = HsDamage.currentProjectile
        val jumping = ship.browserJumping
        val event = damageAreaEvent(ship, proj, location, damage, forceHit)
        val evasion = event and 3
        if (event and PREEMPTED != 0) return when (evasion) { 1 -> true; 2 -> false; else -> ship.pickMissed(0, false) }
        if (evasion == 2) {
            if (!jumping) ship.showDamageTextAt(location, "text_miss", xyz.znix.xftl.rendering.Colour.white)
            return false
        }
        val force = evasion == 1
        val room: Room = ship.rooms.firstOrNull { it.containsShipSpace(location) } ?: return false
        // CustomShips.cpp DamageArea: the room's hull resist (BeamDamageRules.hsHullResist) on the by-value copy.
        var dmg = damage
        val resist = damage.hullDamage > 0 && damage.copy().let { c -> xyz.znix.xftl.weapons.BeamDamageRules.hsHullResist(room, c).also { if (it) dmg = c } }
        if ((jumping || !force) && ship.pickMissed(0, false)) {
            if (!jumping) ship.showDamageTextAt(location, "text_miss", xyz.znix.xftl.rendering.Colour.white)
            return false
        }
        ship.damage(room, dmg, location)
        HsDamage.areaHit(ship, room, location, HsDamage.current?.sourceShipId ?: -1)
        val hit = !damageAreaHit(ship, proj, location, dmg)
        if (resist && hit) ship.showDamageTextAt(room.pixelCentre, "text_resist", xyz.znix.xftl.rendering.Colour.white)
        return hit
    }
}
