// HS GPL-3.0-or-later 1b05a07 lua/modules/hyperspace.i; real BeamWeapon members and CreateBeam.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.weapons.HsProjectileConstruction
import xyz.znix.xftl.hs.weapons.HsWeaponHooks
import xyz.znix.xftl.math.*
import xyz.znix.xftl.weapons.*
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

object LuaAdaptersBeam {
    class TargetableMember(val ship: xyz.znix.xftl.Ship) : LuaTyped { override val luaType get() = A.T_Targetable; @JvmField var targeted = false }
    /** Set by [damageBeamEvent]: whether a DAMAGE_BEAM callback pre-empted the call. */
    @JvmStatic var damageBeamPreempted = false
        private set

    /**
     * CustomDamage.cpp139: DAMAGE_BEAM(ShipManager, Projectile, location, Damage, realNewTile, beamHitType). The
     * callbacks share [location] and [damage] (the hook's own copies); returns the raw integer result, or [hitType]
     * when the chain returns none.
     */
    fun damageBeamEvent(ship: xyz.znix.xftl.Ship, projectile: Any?, location: MutFPoint, damage: Damage,
                        realNewTile: Boolean, hitType: Int): Int {
        damageBeamPreempted = false
        if (!HsLua.hasInt[A.EV_DAMAGE_BEAM]) return hitType
        ret.obj(ship, A.T_ShipManager)
        ret.obj(projectile, A.T_Projectile)
        val point = LuaPointfMember(FGet { location.xf }, FSet { location.xf = it }, FGet { location.yf }, FSet { location.yf = it })
        ret.obj(point, A.T_Pointf)
        ret.obj(damage, A.T_Damage)
        LuaJs.pushboolean(realNewTile)
        LuaJs.pushinteger(hitType.toDouble())
        damageBeamPreempted = LuaJs.fireChain(A.EV_DAMAGE_BEAM, 6, 1)
        val type = if (LuaJs.isinteger(-1)) LuaJs.tointeger(-1).toInt() else hitType
        LuaJs.pop(6)
        return type
    }

    fun install() {
        fun beam(value: Any?) = value as? BeamProjectile ?: throw LuaError("expected BeamWeapon body")
        LuaReg.obj(A.A_WeaponBlueprint_color, ObjGet { (it as AbstractWeaponBlueprint).nativeColor }, ObjSet { o, v ->
            val src = v as? GlColorValue ?: throw LuaError("expected GL_Color")
            val dst = (o as AbstractWeaponBlueprint).nativeColor
            dst.r = src.r; dst.g = src.g; dst.b = src.b; dst.a = src.a
        })
        val colorBefore = LuaReg.objGet[A.A_Projectile_color]!!
        LuaReg.obj(A.A_Projectile_color, ObjGet { o -> if (o is AbstractProjectile) o.nativeColor else colorBefore.get(o) },
            ObjSet { o, v ->
                val src = v as? GlColorValue ?: throw LuaError("expected GL_Color")
                val dst = (o as? AbstractProjectile)?.nativeColor ?: throw LuaError("expected Projectile body")
                dst.r = src.r; dst.g = src.g; dst.b = src.b; dst.a = src.a
            })
        LuaReg.obj(A.A_BeamWeapon_movingTarget, ObjGet { o ->
            beam(o).movingTarget?.let { ship -> LuaViews.cached(ship, A.A_BeamWeapon_movingTarget) { TargetableMember(ship) } }
        }, ObjSet { o, v ->
            beam(o).movingTarget = if (v == null) null else (v as? TargetableMember)?.ship
                ?: throw LuaError("Expected a ship Targetable member")
        })
        fun point(value: Any?): ConstFPoint = when (value) {
            is PointfValue -> ConstFPoint(value.x, value.y)
            is LuaPointfMember -> ConstFPoint(value.x, value.y)
            else -> throw LuaError("expected Pointf")
        }
        LuaReg.method(A.M_ShipManager_DamageBeam, LuaMethod {
            val ship = arg.obj(1) as? xyz.znix.xftl.Ship ?: throw LuaError("expected ShipManager")
            val damage = arg.obj(4) as? Damage ?: throw LuaError("expected Damage")
            ret.bool(BeamDamageRules.damageBeam(ship, point(arg.obj(2)), point(arg.obj(3)), damage))
        })
        fun vector(id: Int, select: (BeamProjectile) -> MutFPoint) {
            LuaReg.obj(id, ObjGet { o ->
                val p = select(beam(o))
                LuaViews.cached(o!!, id) { LuaPointfMember(FGet { p.xf }, FSet { p.xf = it }, FGet { p.yf }, FSet { p.yf = it }) }
            }, ObjSet { o, v -> select(beam(o)).set(point(v)) })
        }
        vector(A.A_BeamWeapon_sub_end) { it.motion.subEnd }; vector(A.A_BeamWeapon_sub_start) { it.motion.subStart }
        vector(A.A_BeamWeapon_shield_end) { it.motion.shieldEnd }; vector(A.A_BeamWeapon_final_end) { it.motion.finalEnd }
        vector(A.A_BeamWeapon_target1) { it.motion.target1 }; vector(A.A_BeamWeapon_target2) { it.motion.target2 }
        vector(A.A_BeamWeapon_lastSmokeAnim) { it.motion.lastSmokeAnim }
        fun number(id: Int, get: (BeamProjectile) -> Float, set: (BeamProjectile, Float) -> Unit) =
            LuaReg.num(id, NumGet { get(beam(it)).toDouble() }, NumSet { o, v -> set(beam(o), v.toFloat()) })
        number(A.A_BeamWeapon_lifespan, { it.motion.lifespan }, { o, v -> o.motion.lifespan = v })
        number(A.A_BeamWeapon_length, { it.motion.length }, { o, v -> o.motion.length = v })
        number(A.A_BeamWeapon_dh, { it.motion.dh }, { o, v -> o.motion.dh = v })
        number(A.A_BeamWeapon_animationTimer, { it.motion.animationTimer }, { o, v -> o.motion.animationTimer = v })
        number(A.A_BeamWeapon_start_heading, { it.motion.startHeading }, { o, v -> o.motion.startHeading = v })
        number(A.A_BeamWeapon_timer, { it.motion.timer }, { o, v -> o.motion.timer = v })
        LuaReg.num(A.A_BeamWeapon_lastDamage, NumGet { beam(it).motion.lastDamage.toDouble() }, NumSet { o, v -> beam(o).motion.lastDamage = v.toInt() })
        LuaReg.num(A.A_BeamWeapon_soundChannel, NumGet { beam(it).motion.soundChannel.toDouble() }, NumSet { o, v -> beam(o).motion.soundChannel = v.toInt() })
        LuaReg.num(A.A_BeamWeapon_movingTargetId, NumGet { beam(it).motion.movingTargetId.toDouble() }, NumSet { o, v -> beam(o).motion.movingTargetId = v.toInt() })
        fun bool(id: Int, get: (BeamProjectile) -> Boolean, set: (BeamProjectile, Boolean) -> Unit) =
            LuaReg.bool(id, { get(beam(it)) }, { o, v -> set(beam(o), v) })
        bool(A.A_BeamWeapon_piercedShield, { it.motion.piercedShield }, { o, v -> o.motion.piercedShield = v })
        bool(A.A_BeamWeapon_oneSpace, { it.motion.oneSpace }, { o, v -> o.motion.oneSpace = v })
        bool(A.A_BeamWeapon_bDamageSuperShield, { it.motion.damageSuperShield }, { o, v -> o.motion.damageSuperShield = v })
        bool(A.A_BeamWeapon_checkedCollision, { it.motion.checkedCollision }, { o, v -> o.motion.checkedCollision = v })
        LuaReg.obj(A.A_BeamWeapon_last_collision, ObjGet { o ->
            LuaViews.cached(beam(o), A.A_BeamWeapon_last_collision) { HsWeaponHooks.collisionView(beam(o).motion.lastCollision) }
        },
            ObjSet { o, v ->
                val src = (v as? HsWeaponHooks.CollisionView)?.response ?: throw LuaError("expected CollisionResponse")
                val into = beam(o).motion.lastCollision
                into.type = src.type; into.point.set(src.point); into.damage = src.damage; into.superDamage = src.superDamage
            })
        LuaReg.methods[A.M_BeamWeapon_Dead] = LuaReg.methods[A.M_Projectile_Dead]
        LuaReg.methods[A.M_BeamWeapon_EnterDestinationSpace] = LuaReg.methods[A.M_Projectile_EnterDestinationSpace]
        LuaReg.methods[A.M_BeamWeapon_CollisionMoving] = LuaReg.methods[A.M_Projectile_CollisionMoving]
        LuaAdaptersArmament.install()
        LuaReg.method(A.M_SpaceManager_CreateBeam, LuaMethod {
            val game = LuaAdapters.inGame() ?: throw LuaError("no game")
            val bp = arg.obj(2) as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
            val position = point(arg.obj(3)); val spaceId = arg.int(4); val ownerId = arg.int(5)
            val first = point(arg.obj(6)); val last = point(arg.obj(7)); val destination = arg.int(8)
            val length = arg.int(9); val heading = arg.float(10)
            val storage = LuaAdapters.shipManager(spaceId) ?: game.player
            val context = HsProjectileConstruction.Context(position, first, storage, ownerId, spaceId, destination)
            val p = HsProjectileConstruction.with(context) {
                // HS Global.cpp84: every ID other than1 resolves to the player, retaining the numeric ID.
                BeamProjectile(game, if (destination == 1) game.enemy else game.player, last, length, heading).also {
                    it.hsOwnerId = ownerId
                    when {
                        spaceId == destination -> it.computeBeamHeading()
                        heading == -1f -> it.setBeamHeading((xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360).toFloat())
                        else -> it.setBeamHeading(heading)
                    }
                    val entry = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360).toFloat()
                    it.entryAngle = Math.toRadians(entry.toDouble()).toFloat(); it.hsExt?.entryAngle = entry
                    it.motion.aimSubEnd(position, heading)
                    it.initialize(bp)
                }
            }
            storage.projectiles += p
            ret.obj(p, A.T_BeamWeapon)
        })
    }
}
