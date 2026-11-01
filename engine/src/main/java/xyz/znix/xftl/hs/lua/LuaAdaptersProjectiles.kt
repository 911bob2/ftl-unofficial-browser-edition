// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 lua/modules/hyperspace.i (Projectile, LaserBlast, Missile,
// BombProjectile, Asteroid, PDSFire, Targetable, Collideable, Projectile_Extend, CustomDamage(Definition)),
// src/game/Gameplay/CustomDamage.cpp (SpaceManager::Create*), ProjectileVTable.cpp. Native bodies: see the classes.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.environment.AsbProjectile
import xyz.znix.xftl.environment.AsteroidProjectile
import xyz.znix.xftl.hs.weapons.HsDamageDef
import xyz.znix.xftl.hs.weapons.HsProjectileConstruction
import xyz.znix.xftl.hs.weapons.HsWeapons
import xyz.znix.xftl.hs.weapons.HsWeaponsLua
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.weapons.*
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Projectile::_targetable (and LaserBlast::movingTarget): the Targetable embedded in its owner. */
class LuaTargetableMember(val owner: Any) : LuaTyped { override val luaType get() = A.T_Targetable }

/** An opaque native pointer member with no Lua members (DamageMessage, ErosionEffect). */
class LuaOpaqueMember(val owner: Any, override val luaType: Int) : LuaTyped

object LuaAdaptersProjectiles {
    private fun p(o: Any?) = o as? AbstractProjectile ?: throw LuaError("expected Projectile body")
    private fun point(value: Any?): ConstFPoint = when (value) {
        is PointfValue -> ConstFPoint(value.x, value.y)
        is LuaPointfMember -> ConstFPoint(value.x, value.y)
        else -> throw LuaError("expected Pointf")
    }
    private fun pushPointf(x: Float, y: Float) = ret.obj(PointfValue(x, y), A.T_Pointf)

    /** The engine body behind a Targetable view or a raw object. */
    /** ShipManager::_targetable.hostile of the player's ship (the oracle's wp5-crew row `enemy`). */
    private const val PLAYER_HOSTILE = true

    fun targetableOwner(value: Any?): Any? = when (value) {
        is LuaTargetableMember -> value.owner
        is LuaAdaptersBeam.TargetableMember -> value.ship
        else -> value
    }

    fun install() {
        projectileMembers()
        derivedMembers()
        targetable()
        collideable()
        customDamage()
        spaceManager()
    }

    private fun projectileMembers() {
        LuaReg.obj(A.A_Projectile__targetable, ObjGet { o ->
            LuaViews.cached(p(o), A.A_Projectile__targetable) { LuaTargetableMember(it) }
        })
        LuaReg.obj(A.A_Projectile_flight_animation, ObjGet { val b = p(it); LuaProjectileAnimation.view(b.flightTracker).also { v -> if (b.hsFlightName != null) v.values[A.A_Animation_animName] = b.hsFlightName } })
        LuaReg.obj(A.A_Projectile_flashTracker, ObjGet { LuaProjectileAnimation.trackerView(p(it).nativeFields.flashTracker) })
        LuaReg.str(A.A_Projectile_hitSolidSound, StrGet { p(it).nativeFields.hitSolidSound },
            StrSet { o, v -> p(o).nativeFields.hitSolidSound = v })
        LuaReg.str(A.A_Projectile_hitShieldSound, StrGet { p(it).nativeFields.hitShieldSound },
            StrSet { o, v -> p(o).nativeFields.hitShieldSound = v })
        LuaReg.bool(A.A_Projectile_bBroadcastTarget, { p(it).nativeFields.broadcastTarget },
            { o, v -> p(o).nativeFields.broadcastTarget = v })

        // Projectile::StartedDeath reports startedDeath and clears it (1.6.14 0x45baf0).
        LuaReg.method(A.M_Projectile_StartedDeath, LuaMethod { ret.bool(p(arg.obj(1)).nativeStartedDeath()) })
        // Projectile::ForceRenderLayer (HS: crystal shards 1, else -1); PDSFire keeps its own native override.
        val renderLayer = LuaMethod { ret.num(p(arg.obj(1)).nativeForceRenderLayer()) }
        LuaReg.method(A.M_Projectile_ForceRenderLayer, renderLayer)
        LuaReg.method(A.M_PDSFire_ForceRenderLayer, renderLayer)
        // GetOwnerId is virtual: BombProjectile returns -1 (0x761ca0).
        val owner = LuaMethod { ret.num(p(arg.obj(1)).nativeCollisionOwnerId) }
        LuaReg.method(A.M_Projectile_GetOwnerId, owner)
        LuaReg.method(A.M_BombProjectile_GetOwnerId, owner)
        // Projectile::GetRandomTargettingPoint returns the position (0x755f10).
        LuaReg.method(A.M_Projectile_GetRandomTargettingPoint, LuaMethod {
            val at = p(arg.obj(1)).position; pushPointf(at.xf, at.yf)
        })
        val spin = LuaMethod { p(arg.obj(1)).nativeSetSpin(arg.float(2)); 0 }
        LuaReg.method(A.M_Projectile_SetSpin, spin)
        LuaReg.method(A.M_LaserBlast_SetSpin, spin)
        // Projectile::SetMovingTarget is empty (0x755eb0); bodies that keep one override it.
        LuaReg.method(A.M_Projectile_SetMovingTarget, LuaMethod {
            p(arg.obj(1)).nativeSetMovingTarget(targetableOwner(arg.obj(2))); 0
        })
        // Projectile::SetWeaponAnimation is empty for every body but BeamWeapon (hyperspace.i).
        if (LuaReg.methods[A.M_Projectile_SetWeaponAnimation] == null)
            LuaReg.method(A.M_Projectile_SetWeaponAnimation, LuaMethod { p(arg.obj(1)); 0 })
        // Projectile::RandomSidePoint() (static, 1.6.14 0x45d0b0): side = random32() % 4, then one more draw for the
        // position along it: (-400, r%800), (800, (r%800)/2), (r%800, -400), (r%800, 800).
        LuaReg.method(A.M_Projectile_RandomSidePoint__SWIG_0, LuaMethod {
            val side = HsWeapons.r32() % 4
            val value = xyz.znix.xftl.weapons.BeamMotion.entryPoint(side, HsWeapons.r32())
            pushPointf(value.xf, value.yf)
        })
        // RandomSidePoint(side) (0x45ce70) with the hyperspace.i contract side >= 0, side < 4.
        LuaReg.method(A.M_Projectile_RandomSidePoint__SWIG_1, LuaMethod {
            val side = arg.int(1)
            if (side < 0) throw LuaError("Contract violation: require: (arg1>=0)")
            if (side >= 4) throw LuaError("Contract violation: require: (arg1<4)")
            val value = xyz.znix.xftl.weapons.BeamMotion.entryPoint(side, HsWeapons.r32())
            pushPointf(value.xf, value.yf)
        })
        // OnRenderSpecific(spaceId): draws the body now when it is in that space (only while a render event runs).
        val renderSpecific = LuaMethod {
            val body = p(arg.obj(1)); val space = arg.int(2)
            val g = HsLua.graphics
            val ship = body.hsCurrentSpace
            if (g != null && ship != null && body.hsCurrentSpaceId == space) {
                LuaGraphics.flushQuads()
                body.render(g, ship)
            }
            0
        }
        for (id in intArrayOf(A.M_Projectile_OnRenderSpecific, A.M_LaserBlast_OnRenderSpecific, A.M_Missile_OnRenderSpecific,
            A.M_BombProjectile_OnRenderSpecific, A.M_Asteroid_OnRenderSpecific, A.M_PDSFire_OnRenderSpecific))
            LuaReg.method(id, renderSpecific)
        // Projectile::OnRender(spaceId) (1.6.14 0x45d290): flashTracker.Update(), then OnRenderSpecific(spaceId).
        LuaReg.method(A.M_Projectile_OnRender, LuaMethod { n ->
            p(arg.obj(1)).nativeFields.flashTracker.advance(LuaFps.speedFactor * .0625f)
            renderSpecific.call(n)
        })
        // LaserBlast::ComputeHeading (0x4589a0) leads a moving target; other bodies keep the base direction.
        val baseHeading = LuaReg.methods[A.M_Projectile_ComputeHeading]
        val heading = LuaMethod { n ->
            val body = arg.obj(1)
            if (body is LaserBlastProjectile && body.movingTarget != null) { body.nativeLaserComputeHeading(LuaFps.speedFactor * .0625f); 0 }
            else baseHeading?.call(n) ?: 0
        }
        LuaReg.method(A.M_Projectile_ComputeHeading, heading)
        LuaReg.method(A.M_LaserBlast_ComputeHeading, heading)
        // A bomb and a PDSFire are never collision receivers (0x44fc30 / 0x76af00): their override answers type 0.
        val noCollision = LuaMethod {
            p(arg.obj(1)); ret.obj(xyz.znix.xftl.hs.weapons.HsWeaponHooks.collisionView(ProjectileCollisionResponse()), A.T_CollisionResponse)
        }
        LuaReg.method(A.M_BombProjectile_CollisionMoving, noCollision)
        LuaReg.method(A.M_PDSFire_CollisionMoving, noCollision)
    }

    private fun derivedMembers() {
        fun laser(o: Any?) = o as? NativeLaserBlastFields ?: throw LuaError("expected LaserBlast body")
        LuaReg.num(A.A_LaserBlast_spinAngle, NumGet { laser(it).spinAngle.toDouble() }, NumSet { o, v -> laser(o).spinAngle = v.toFloat() })
        LuaReg.num(A.A_LaserBlast_spinSpeed, NumGet { laser(it).spinSpeed.toDouble() }, NumSet { o, v -> laser(o).spinSpeed = v.toFloat() })
        LuaReg.obj(A.A_LaserBlast_movingTarget, ObjGet { o ->
            val target = laser(o).movingTarget ?: return@ObjGet null
            if (target is AbstractProjectile) LuaViews.cached(target, A.A_Projectile__targetable) { LuaTargetableMember(it) }
            else LuaViews.cached(target, A.A_LaserBlast_movingTarget) { LuaTargetableMember(it) }
        }, ObjSet { o, v -> laser(o).movingTarget = targetableOwner(v) })

        fun bomb(o: Any?) = o as? BombProjectile ?: throw LuaError("expected BombProjectile body")
        LuaReg.bool(A.A_BombProjectile_bMissed, { bomb(it).bMissed }, { o, v -> bomb(o).bMissed = v })
        LuaReg.bool(A.A_BombProjectile_bSuperShield, { bomb(it).bSuperShield }, { o, v -> bomb(o).bSuperShield = v })
        LuaReg.bool(A.A_BombProjectile_superShieldBypass, { bomb(it).superShieldBypass }, { o, v -> bomb(o).superShieldBypass = v })
        LuaReg.num(A.A_BombProjectile_explosiveDelay, NumGet { bomb(it).explosiveDelay.toDouble() },
            NumSet { o, v -> bomb(o).explosiveDelay = v.toFloat() })
        // missMessage is a DamageMessage pointer (no Lua members): nil until a miss creates one.
        LuaReg.obj(A.A_BombProjectile_missMessage, ObjGet { o ->
            val b = bomb(o)
            if (b.missMessageTime == null) null else LuaViews.cached(b, A.A_BombProjectile_missMessage) { LuaOpaqueMember(it, 282) }
        })

        fun asteroid(o: Any?) = o as? AsteroidProjectile ?: throw LuaError("expected Asteroid body")
        LuaReg.num(A.A_Asteroid_angle, NumGet { asteroid(it).angle.toDouble() }, NumSet { o, v -> asteroid(o).angle = v.toFloat() })
        LuaReg.obj(A.A_Asteroid_imageId, ObjGet { o ->
            val a = asteroid(o)
            a.imageTexture as? LuaTexture ?: LuaGraphics.texture("asteroids/asteroid${a.imageId}.png")
        }, ObjSet { o, v -> asteroid(o).imageTexture = v as? LuaTexture ?: throw LuaError("expected GL_Texture") })

        fun pds(o: Any?) = o as? AsbProjectile ?: throw LuaError("expected PDSFire body")
        LuaReg.obj(A.A_PDSFire_startPoint, ObjGet { o ->
            val body = pds(o)
            LuaViews.cached(body, A.A_PDSFire_startPoint) {
                LuaPointfMember(FGet { body.startPoint.xf }, FSet { body.startPoint.xf = it },
                    FGet { body.startPoint.yf }, FSet { body.startPoint.yf = it })
            }
        }, ObjSet { o, v -> pds(o).startPoint.set(point(v)) })
        LuaReg.bool(A.A_PDSFire_passedTarget, { pds(it).pdsPassedTarget }, { o, v -> pds(o).pdsPassedTarget = v })
        LuaReg.bool(A.A_PDSFire_missed, { pds(it).showsMiss }, { o, v -> pds(o).showsMiss = v })
        LuaReg.num(A.A_PDSFire_currentScale, NumGet { pds(it).scale.toDouble() }, NumSet { _, _ ->
            throw LuaError("PDSFire.currentScale is recomputed every frame from the distance flown") })
        // The constructor's GetAnimation("explosion_random") pick (0x45d360), the hit's explosion: the body's own
        // tracker (done and not running until the hit starts it), with the picked animation's descriptor.
        LuaReg.obj(A.A_PDSFire_explosionAnimation, ObjGet { o ->
            val body = pds(o)
            val fresh = body.explosionTracker.luaView == null
            LuaProjectileAnimation.view(body.explosionTracker).also { view ->
                val spec = body.explosionSpec
                if (fresh && spec != null) {
                    val source = LuaAnim.create(spec)
                    val from = LuaStructs.member(source, A.A_Animation_info) as LuaStruct
                    (LuaStructs.member(view, A.A_Animation_info) as LuaStruct).values.putAll(from.values)
                    view.values[A.A_Animation_animName] = source.values[A.A_Animation_animName]
                }
                // Animation.position: the top left the hit set (CollisionCheck 0x456fd7).
                val position = LuaStructs.member(view, A.A_Animation_position) as LuaStruct
                position.values[A.A_Pointf_x] = body.explosionPosition.xf.toDouble()
                position.values[A.A_Pointf_y] = body.explosionPosition.yf.toDouble()
            }
        })
    }

    private fun targetable() {
        // Targetable thunks of a projectile (1.6.14 0x85d210-0x85d280) and the base defaults (0x756ce0-0x756ef0).
        fun body(o: Any?): Any = targetableOwner(o) ?: throw LuaError("expected Targetable")
        fun projectile(o: Any?): AbstractProjectile =
            body(o) as? AbstractProjectile ?: throw LuaError("Targetable of a ${body(o).javaClass.simpleName} is not available")
        fun shipId(o: Any?): Int? = (body(o) as? Ship)?.let { HsWeapons.shipId(it) }
        LuaReg.num(A.A_Targetable_type, NumGet { o ->
            when (val b = body(o)) { is AbstractProjectile -> b.targetableType.toDouble(); is Ship -> 0.0
                else -> throw LuaError("Targetable.type of a ${b.javaClass.simpleName} is not available") }
        }, NumSet { o, v -> projectile(o).targetableType = v.toInt() })
        // A ship's Targetable (ShipManager::_targetable): hostile is the enemy's hostility; the player's own is PLAYER_HOSTILE.
        LuaReg.bool(A.A_Targetable_hostile, { o ->
            when (val b = body(o)) { is Ship -> if (b === b.sys.player) PLAYER_HOSTILE else b === b.sys.enemy && b.sys.isEnemyHostile
                else -> projectile(o).nativeFields.hostile }
        }, { o, v -> projectile(o).nativeFields.hostile = v })
        LuaReg.bool(A.A_Targetable_targeted, { projectile(it).nativeFields.targeted }, { o, v -> projectile(o).nativeFields.targeted = v })
        LuaReg.method(A.M_Targetable_GetWorldCenterPoint, LuaMethod {
            val at = projectile(arg.obj(1)).position; pushPointf(at.xf, at.yf)
        })
        LuaReg.method(A.M_Targetable_GetRandomTargettingPoint, LuaMethod {
            val at = projectile(arg.obj(1)).position; pushPointf(at.xf, at.yf)
        })
        LuaReg.method(A.M_Targetable_GetAllTargettingPoints, LuaMethod {
            val at = projectile(arg.obj(1)).position
            ret.obj(ListView(A.T_vector_Pointf, A.T_Pointf, arrayListOf<Any?>(PointfValue(at.xf, at.yf))), A.T_vector_Pointf)
        })
        LuaReg.method(A.M_Targetable_GetShieldShape, LuaMethod {
            projectile(arg.obj(1))
            val shape = LuaStructs.create(A.T_Ellipse)
            (LuaStructs.member(shape, A.A_Ellipse_center) as? LuaStruct)?.let { LuaStructs.setFields(it, -1.0, -1.0) }
            shape.values[A.A_Ellipse_a] = 0.0; shape.values[A.A_Ellipse_b] = 0.0
            ret.obj(shape, A.T_Ellipse)
        })
        // A by-value ShieldPower (a LuaStruct) next to the ship's live view: plain struct fields for the struct.
        for (id in intArrayOf(A.A_ShieldPower_first, A.A_ShieldPower_second)) {
            val get = LuaReg.numGet[id]; val set = LuaReg.numSet[id]
            LuaReg.num(id, NumGet { o -> if (o is LuaStruct) (o.values[id] as? Double) ?: 0.0 else get!!.get(o) },
                NumSet { o, v -> if (o is LuaStruct) o.values[id] = v else (set ?: throw LuaError("This variable is immutable")).set(o, v) })
        }
        val superGet = LuaReg.objGet[A.A_ShieldPower_super]; val superSet = LuaReg.objSet[A.A_ShieldPower_super]
        LuaReg.obj(A.A_ShieldPower_super, ObjGet { o -> if (o is LuaStruct) LuaStructs.member(o, A.A_ShieldPower_super) else superGet!!.get(o) },
            ObjSet { o, v -> if (o is LuaStruct) throw LuaError("ShieldPower.super is a member pair") else superSet?.set(o, v) })
        LuaReg.method(A.M_Targetable_GetShieldPower, LuaMethod {
            projectile(arg.obj(1))
            val power = LuaStructs.create(A.T_ShieldPower)
            power.values[A.A_ShieldPower_first] = 0.0; power.values[A.A_ShieldPower_second] = 0.0
            (LuaStructs.member(power, A.A_ShieldPower_super) as? LuaStruct)?.let { LuaStructs.setFields(it, 0.0, 0.0) }
            ret.obj(power, A.T_ShieldPower)
        })
        LuaReg.method(A.M_Targetable_GetSpaceId, LuaMethod {
            shipId(arg.obj(1))?.let { return@LuaMethod ret.num(it) }
            ret.num(projectile(arg.obj(1)).hsCurrentSpaceId)
        })
        LuaReg.method(A.M_Targetable_GetOwnerId, LuaMethod {
            shipId(arg.obj(1))?.let { return@LuaMethod ret.num(it) }
            // The Targetable thunk calls the virtual GetOwnerId (a bomb's is -1, as the HS oracle shows).
            ret.num(projectile(arg.obj(1)).nativeCollisionOwnerId)
        })
        LuaReg.method(A.M_Targetable_GetSelfId, LuaMethod {
            shipId(arg.obj(1))?.let { return@LuaMethod ret.num(it) }
            val body = projectile(arg.obj(1)); ret.num(body.hsExt?.selfId ?: body.worldOrder)
        })
        LuaReg.method(A.M_Targetable_GetSpeed, LuaMethod {
            val speed = projectile(arg.obj(1)).nativeFrameSpeed; pushPointf(speed.xf, speed.yf)
        })
        // A ship's Targetable: ShipManager::IsCloaked (its cloak is on); a projectile is never cloaked
        LuaReg.method(A.M_Targetable_IsCloaked, LuaMethod {
            (arg.obj(1) as? LuaAdaptersBeam.TargetableMember)?.let { return@LuaMethod ret.bool(it.ship.isCloakActive) }
            projectile(arg.obj(1)); ret.bool(false)
        })
        LuaReg.method(A.M_Targetable_DamageTarget, LuaMethod { projectile(arg.obj(1)); 0 })
        LuaReg.method(A.M_Targetable_GetIsDying, LuaMethod { projectile(arg.obj(1)); ret.bool(false) })
        LuaReg.method(A.M_Targetable_GetIsJumping, LuaMethod { projectile(arg.obj(1)); ret.bool(false) })
        LuaReg.method(A.M_Targetable_ValidTarget, LuaMethod {
            val body = projectile(arg.obj(1)); ret.bool(body.hsExt?.startedDeath != true)
        })
        LuaReg.method(A.M_Targetable_GetShape, LuaMethod {
            projectile(arg.obj(1))
            val rect = LuaStructs.create(A.T_Rect)
            rect.values[A.A_Rect_x] = 0.0; rect.values[A.A_Rect_y] = 0.0; rect.values[A.A_Rect_w] = 0.0; rect.values[A.A_Rect_h] = 0.0
            ret.obj(rect, A.T_Rect)
        })
    }

    private fun collideable() {
        // A projectile's Collideable defaults (1.6.14 0x757900-0x757990): no damage, never dodges, no super shield
        // ((-1, -1)), SetTempVision does nothing, every location is valid. Other owners keep their adapters.
        fun wrap(id: Int, projectile: (AbstractProjectile) -> Int) {
            val previous = LuaReg.methods[id]
            LuaReg.method(id, LuaMethod { n ->
                val self = arg.obj(1)
                if (self is AbstractProjectile) projectile(self)
                else previous?.call(n) ?: throw LuaError("Collideable method not available for ${self?.javaClass?.simpleName}")
            })
        }
        wrap(A.M_Collideable_DamageBeam) { ret.bool(false) }
        wrap(A.M_Collideable_DamageArea) { ret.bool(false) }
        wrap(A.M_Collideable_DamageShield) { ret.bool(false) }
        wrap(A.M_Collideable_GetDodged) { ret.bool(false) }
        wrap(A.M_Collideable_GetSuperShield) { pushPointf(NativeCollideableDefaults.NO_SUPER_SHIELD, NativeCollideableDefaults.NO_SUPER_SHIELD) }
        wrap(A.M_Collideable_SetTempVision) { 0 }
        wrap(A.M_Collideable_ValidTargetLocation) { ret.bool(true) }
        // HS 1b05a07 overrides ValidTargetLocation nowhere; ShipManager's (vtable +0x30) answered true both at a room
        // centre and outside every room in the native 1.6.14 bodies capture, so a ship's Collideable answers true too.
        val validPrevious = LuaReg.methods[A.M_Collideable_ValidTargetLocation]
        LuaReg.method(A.M_Collideable_ValidTargetLocation, LuaMethod { n ->
            if (targetableOwner(arg.obj(1)) is Ship) ret.bool(true) else validPrevious!!.call(n)
        })
        wrap(A.M_Collideable_GetSpaceId) { ret.num(it.hsCurrentSpaceId) }
        wrap(A.M_Collideable_GetSelfId) { ret.num(it.hsExt?.selfId ?: it.worldOrder) }
        wrap(A.M_Collideable_GetOwnerId) { ret.num(it.nativeCollisionOwnerId) }
        wrap(A.M_Collideable_CollisionMoving) { body ->
            val response = body.nativeCollisionMoving(point(arg.obj(3)), (arg.obj(4) as Damage).copy())
            ret.obj(xyz.znix.xftl.hs.weapons.HsWeaponHooks.collisionView(response), A.T_CollisionResponse)
        }
    }

    private fun customDamage() {
        fun d(o: Any?) = o as? HsDamageDef ?: throw LuaError("expected CustomDamageDefinition")
        LuaReg.method(A.M_new_CustomDamageDefinition, LuaMethod { ret.obj(HsDamageDef(), A.T_CustomDamageDefinition) })
        LuaReg.method(A.M_CustomDamageDefinition_GiveId, LuaMethod { d(arg.obj(1)).giveId(); 0 })
        LuaReg.num(A.A_CustomDamageDefinition_idx, NumGet { d(it).idx.toDouble() })
        LuaReg.num(A.A_CustomDamageDefinition_accuracyMod, NumGet { d(it).accuracyMod.toDouble() }, NumSet { o, v -> d(o).accuracyMod = v.toInt() })
        LuaReg.num(A.A_CustomDamageDefinition_droneAccuracyMod, NumGet { d(it).droneAccuracyMod.toDouble() }, NumSet { o, v -> d(o).droneAccuracyMod = v.toInt() })
        LuaReg.bool(A.A_CustomDamageDefinition_noSysDamage, { d(it).noSysDamage }, { o, v -> d(o).noSysDamage = v })
        LuaReg.bool(A.A_CustomDamageDefinition_noPersDamage, { d(it).noPersDamage }, { o, v -> d(o).noPersDamage = v })
        LuaReg.bool(A.A_CustomDamageDefinition_ionBeamFix, { d(it).ionBeamFix }, { o, v -> d(o).ionBeamFix = v })
        LuaReg.num(A.A_CustomDamageDefinition_statBoostChance, NumGet { d(it).statBoostChance.toDouble() }, NumSet { o, v -> d(o).statBoostChance = v.toInt() })
        LuaReg.num(A.A_CustomDamageDefinition_roomStatBoostChance, NumGet { d(it).roomStatBoostChance.toDouble() }, NumSet { o, v -> d(o).roomStatBoostChance = v.toInt() })
        LuaReg.num(A.A_CustomDamageDefinition_erosionChance, NumGet { d(it).erosionChance.toDouble() }, NumSet { o, v -> d(o).erosionChance = v.toInt() })
        LuaReg.num(A.A_CustomDamageDefinition_crewSpawnChance, NumGet { d(it).crewSpawnChance.toDouble() }, NumSet { o, v -> d(o).crewSpawnChance = v.toInt() })
        // ErosionEffect has no Lua members (hyperspace.i): an opaque view of the definition's effect.
        LuaReg.obj(A.A_CustomDamageDefinition_erosionEffect, ObjGet { o ->
            LuaViews.cached(d(o), A.A_CustomDamageDefinition_erosionEffect) { LuaOpaqueMember(it, 285) }
        })
        // CustomDamage::def is the definition pointer; Clear() restores the default definition only.
        fun ext(o: Any?) = (o as? HsWeaponsLua.CustomDamageView)?.ext ?: throw LuaError("expected CustomDamage")
        LuaReg.obj(A.A_CustomDamage_def, ObjGet { ext(it).def }, ObjSet { o, v -> ext(o).def = d(v) })
        LuaReg.method(A.M_CustomDamage_Clear, LuaMethod { ext(arg.obj(1)).def = HsDamageDef.DEFAULT; 0 })
        // new CustomDamage(): def = &defaultDef, sourceShipId -1, accuracies 0 (CustomDamage.h); no projectile behind it.
        LuaReg.method(A.M_new_CustomDamage, LuaMethod {
            ret.obj(HsWeaponsLua.CustomDamageView(xyz.znix.xftl.hs.weapons.HsProjectileExt(detached = true)), A.T_CustomDamage)
        })
    }

    private fun spaceManager() {
        fun game() = LuaAdapters.inGame() ?: throw LuaError("no game")
        fun storage(id: Int): Ship = LuaAdapters.shipManager(id) ?: game().player
        fun room(ship: Ship, at: ConstFPoint) =
            ship.rooms.firstOrNull { it.containsShipSpace(xyz.znix.xftl.math.ConstPoint(at.xf.toInt(), at.yf.toInt())) } ?: ship.rooms.first()

        // HS CreateAsteroid: constructor, owner, destination/target, heading; three sound picks; no Initialize.
        LuaReg.method(A.M_SpaceManager_CreateAsteroid, LuaMethod {
            val position = point(arg.obj(2)); val space = arg.int(3); val ownerId = arg.int(4)
            val target = point(arg.obj(5)); val targetSpace = arg.int(6); val heading = arg.float(7)
            val destination = storage(targetSpace)
            // Asteroid(position, space) passes current -1 and destination/targetId = space to the base constructor.
            val context = HsProjectileConstruction.Context(position, target, storage(space), -1, -1, space)
            val body = HsProjectileConstruction.with(context) { AsteroidProjectile(room(destination, target)) }
            body.hsCurrentSpace = storage(space); body.hsCurrentSpaceId = space
            body.hsOwnerId = ownerId
            body.hsDestinationSpaceId = targetSpace
            body.setInitialPath(position, target)
            if (space != targetSpace) body.hsSetHeading(if (heading == -1f) (HsWeapons.r32() % 360).toFloat() else heading)
            body.pickSounds()
            ProjectileRuntime.added(body, storage(space))
            ret.obj(body, A.T_Asteroid)
        })
        // HS CreateMissile: the Missile constructor takes the heading; Initialize(weapon).
        LuaReg.method(A.M_SpaceManager_CreateMissile, LuaMethod {
            val bp = arg.obj(2) as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
            val position = point(arg.obj(3)); val space = arg.int(4); val ownerId = arg.int(5)
            val target = point(arg.obj(6)); val targetSpace = arg.int(7); val heading = arg.float(8)
            val context = HsProjectileConstruction.Context(position, target, storage(space), ownerId, space, targetSpace)
            val body = HsProjectileConstruction.with(context) {
                MissileBody(bp, room(storage(targetSpace), target), initialize = false)
            }
            body.hsOwnerId = ownerId
            body.setInitialPath(position, target)
            // The Missile constructor takes the heading (computed when it is -1 in the same space).
            if (heading != -1f) body.hsSetHeading(heading)
            body.hsInitialize(bp, body.defaultSpeed)
            ProjectileRuntime.added(body, storage(space))
            ret.obj(body, A.T_Missile)
        })
        // HS CreateBomb: off-screen in space 1 - targetSpace; Initialize(weapon); the flight animation does not loop.
        LuaReg.method(A.M_SpaceManager_CreateBomb, LuaMethod {
            val bp = arg.obj(2) as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
            val ownerId = arg.int(3); val target = point(arg.obj(4)); val targetSpace = arg.int(5)
            val destination = storage(targetSpace)
            val context = HsProjectileConstruction.Context(BombProjectile.OFF_SCREEN, target, storage(1 - targetSpace),
                ownerId, 1 - targetSpace, targetSpace)
            val body = HsProjectileConstruction.with(context) { BombProjectile(bp, room(destination, target), false) }
            body.hsOwnerId = ownerId
            ProjectileRuntime.added(body, storage(1 - targetSpace))
            body.hsCurrentSpaceId = 1 - targetSpace // space 1 - targetSpace even without a ship there
            ret.obj(body, A.T_BombProjectile)
        })
        // HS CreatePDSFire(weapon, position, target, targetSpace, smoke): new PDSFire(position, targetSpace, target) in
        // the player's space, Initialize(weapon), pushed to the projectiles; smoke adds a fire_smoke puff (HsPdsSmoke).
        LuaReg.method(A.M_SpaceManager_CreatePDSFire, LuaMethod {
            val bp = arg.obj(2) as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
            val position = when (val v = arg.obj(3)) {
                is PointValue -> xyz.znix.xftl.math.ConstPoint(v.x, v.y)
                is LuaStruct -> xyz.znix.xftl.math.ConstPoint(((v.values[A.A_Point_x] as? Double) ?: 0.0).toInt(),
                    ((v.values[A.A_Point_y] as? Double) ?: 0.0).toInt())
                else -> throw LuaError("expected Point")
            }
            val target = point(arg.obj(4)); val targetSpace = arg.int(5)
            val body = AsbProjectile.create(bp, storage(targetSpace), targetSpace == 0, position, target)
            ProjectileRuntime.added(body, game().player)
            if (arg.bool(6)) xyz.znix.xftl.hs.weapons.HsPdsSmoke.add(game(), position.x, position.y)
            ret.obj(body, A.T_PDSFire)
        })
        // Save/LoadProjectile take a native FileHelper descriptor, which the Lua API never hands out; the browser
        // saves bodies with their ship's projectiles (as for beams).
        for (m in intArrayOf(A.M_Projectile_SaveProjectile, A.M_Projectile_LoadProjectile, A.M_LaserBlast_SaveProjectile,
                A.M_LaserBlast_LoadProjectile, A.M_BombProjectile_SaveProjectile, A.M_BombProjectile_LoadProjectile,
                A.M_PDSFire_SaveProjectile, A.M_PDSFire_LoadProjectile))
            LuaReg.method(m, LuaMethod { p(arg.obj(1)); HS.unsupported("lua", "method", A.methodName(m) + " (FileHelper)"); 0 })
        // HS CreateBurstProjectile: a LaserBlast with the piece image; a fake piece has zero damage and a quarter
        // scale death animation; a real one broadcasts its target when the player owns it.
        LuaReg.method(A.M_SpaceManager_CreateBurstProjectile, LuaMethod {
            val bp = arg.obj(2) as? AbstractWeaponBlueprint ?: throw LuaError("expected WeaponBlueprint")
            val image = arg.str(3) ?: ""; val fake = arg.bool(4)
            val position = point(arg.obj(5)); val space = arg.int(6); val ownerId = arg.int(7)
            val target = point(arg.obj(8)); val targetSpace = arg.int(9); val heading = arg.float(10)
            val context = HsProjectileConstruction.Context(position, target, storage(space), ownerId, space, targetSpace)
            val body = HsProjectileConstruction.with(context) {
                LaserBlastProjectile(bp, room(storage(targetSpace), target), image)
            }
            body.hsOwnerId = ownerId
            body.setInitialPath(position, target)
            if (space != targetSpace) body.hsSetHeading(heading)
            body.nativeBurstFlight()
            if (fake) body.fakeBurst = true else body.nativeFields.broadcastTarget = ownerId == 0
            ProjectileRuntime.added(body, storage(space))
            ret.obj(body, A.T_LaserBlast)
        })
    }
}
