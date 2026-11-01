// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 lua/modules/hyperspace.i and
// src/game/Gameplay/CustomDrones.cpp, src/game/Ship/CustomCrew.cpp (GetSavedPosition).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.weapons.*
import xyz.znix.xftl.weapons.*
import xyz.znix.xftl.math.*
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Weapon/drone family registrations; native member state remains owned by the gameplay objects. */
object LuaAdaptersWeapons {
    fun install() {
        LuaProjectileAnimation.install()
        projectileMembers()
        collisionMembers()
        LuaReg.obj(A.A_SpaceDrone__collideable, ObjGet { o ->
            LuaViews.cached(o!!, A.A_SpaceDrone__collideable) { LuaCollideableMember(it) }
        })
        fun collider(o: Any?) = HsDrones.collisionBody((o as? LuaCollideableMember)?.owner ?: o)
            ?: throw LuaError("SpaceDrone has no collision body")
        val owner = LuaMethod { ret.num(HsWeapons.shipId(collider(arg.obj(1)).collisionOwner)) }
        val space = LuaMethod { ret.num(collider(arg.obj(1)).collisionSpaceId) }
        val self = LuaMethod { ret.num(collider(arg.obj(1)).collisionSelfId) }
        LuaReg.method(A.M_Collideable_GetOwnerId, owner); LuaReg.method(A.M_SpaceDrone_GetOwnerId, owner)
        LuaReg.method(A.M_Collideable_GetSpaceId, space); LuaReg.method(A.M_SpaceDrone_GetSpaceId, space)
        LuaReg.method(A.M_Collideable_GetSelfId, self); LuaReg.method(A.M_SpaceDrone_GetSelfId, self)
        // HackingSystem owns its HackingDrone independently of the equipped AbstractDrone inventory.
        val blueprintBefore = LuaReg.objGet[A.A_Drone_blueprint]!!
        LuaReg.obj(A.A_Drone_blueprint, ObjGet { o ->
            if (o is HsHackingDroneCollisionBody) o.blueprint else blueprintBefore.get(o)
        })
        val ownerBefore = LuaReg.numGet[A.A_Drone_iShipId]!!
        LuaReg.num(A.A_Drone_iShipId, NumGet { o ->
            if (o is HsHackingDroneCollisionBody) HsWeapons.shipId(o.collisionOwner).toDouble() else ownerBefore.get(o)
        })
        val selfBefore = LuaReg.numGet[A.A_Drone_selfId]!!
        LuaReg.num(A.A_Drone_selfId, NumGet { o ->
            if (o is HsHackingDroneCollisionBody) o.collisionSelfId.toDouble() else selfBefore.get(o)
        })
        val poweredBefore = LuaReg.numGet[A.A_Drone_powered]!!
        LuaReg.num(A.A_Drone_powered, NumGet { o ->
            if (o is HsHackingDroneCollisionBody) if (o.collisionPowered) 1.0 else 0.0 else poweredBefore.get(o)
        })
        val droneCollision = LuaMethod {
            val response = HsDrones.collisionMoving(collider(arg.obj(1)), point(arg.obj(3)), arg.obj(4) as Damage)
            ret.obj(HsWeaponHooks.collisionView(response), A.T_CollisionResponse)
        }
        LuaReg.method(A.M_Collideable_CollisionMoving, droneCollision)
        LuaReg.method(A.M_SpaceDrone_CollisionMoving, droneCollision)
        LuaReg.method(A.M_BoarderPodDrone_CollisionMoving, droneCollision)
        LuaReg.method(A.M_HackingDrone_CollisionMoving, droneCollision)
        LuaReg.obj(A.A_HackingSystem_drone, ObjGet { (it as xyz.znix.xftl.systems.Hacking).hsCollisionBody })
        LuaReg.bool(A.A_HackingDrone_arrived, { (collider(it) as HsHackingDroneCollisionBody).arrived },
            { o, value -> (collider(o) as HsHackingDroneCollisionBody).arrived = value })
        LuaReg.bool(A.A_BoarderPodDrone_bDeliveredDrone, { (it as xyz.znix.xftl.drones.BoardingDrone).hsPodDelivered },
            { o, value -> (o as xyz.znix.xftl.drones.BoardingDrone).hsPodDelivered = value })
        LuaReg.method(A.M_ShipManager_GetDodgeFactor, LuaMethod { ret.num((arg.obj(1) as xyz.znix.xftl.Ship).dodgeFactor) })
        LuaReg.method(A.M_ShipManager_GetDodged, LuaMethod { ret.bool((arg.obj(1) as xyz.znix.xftl.Ship).pickMissed()) })
        // GetSavedPosition returns a Slot by value. Its returned view is distinct from the stored station.
        LuaReg.method(A.M_CrewMember_GetSavedPosition, LuaMethod {
            val member = arg.obj(1) as AbstractCrew
            val point = member.getReportedStation()?.let { member.room.ship.shipToRoomPos(it) }
            val slot = LuaStructs.create(A.T_Slot)
            slot.values[A.A_Slot_roomId] = (point?.room?.id ?: -1).toDouble()
            slot.values[A.A_Slot_slotId] = (point?.let { it.room.pointToSlot(it) } ?: -1).toDouble()
            (LuaStructs.member(slot, A.A_Slot_worldLocation) as? LuaStruct)?.let {
                LuaStructs.setFields(it, (point?.let { p -> p.offsetX + 17 } ?: -1).toDouble(),
                    (point?.let { p -> p.offsetY + 17 } ?: -1).toDouble())
            }
            ret.obj(slot, A.T_Slot)
        })
        LuaReg.method(A.M_CrewMember_SavePosition, LuaMethod {
            val member = arg.obj(1) as AbstractCrew
            member.savedPosition = member.currentSlotPoint.shipPoint
            0
        })
        LuaReg.method(A.M_CrewMember_ClearPosition, LuaMethod {
            (arg.obj(1) as AbstractCrew).savedPosition = null
            0
        })
        LuaReg.method(A.M_CrewMember_RestorePosition, LuaMethod {
            val member = arg.obj(1) as AbstractCrew
            val point = member.savedPosition?.let { member.room.ship.shipToRoomPos(it) }
            ret.bool(member.health > 0f && point != null && member.setTargetRoom(point.room, point))
        })
    }

    private fun point(value: Any?): ConstFPoint = when (value) {
        is PointfValue -> ConstFPoint(value.x, value.y)
        is LuaPointfMember -> ConstFPoint(value.x, value.y)
        else -> throw LuaError("expected Pointf")
    }

    private fun collisionMembers() {
        LuaStructs.create(A.T_CollisionResponse)
        fun integer(id: Int, get: (ProjectileCollisionResponse) -> Int, set: (ProjectileCollisionResponse, Int) -> Unit) {
            val beforeGet = LuaReg.numGet[id]!!; val beforeSet = LuaReg.numSet[id]!!
            LuaReg.num(id, NumGet { if (it is HsWeaponHooks.CollisionView) get(it.response).toDouble() else beforeGet.get(it) },
                NumSet { o, v -> if (o is HsWeaponHooks.CollisionView) set(o.response, v.toInt()) else beforeSet.set(o, v) })
        }
        integer(A.A_CollisionResponse_collision_type, { it.type }, { o, v -> o.type = v })
        integer(A.A_CollisionResponse_damage, { it.damage }, { o, v -> o.damage = v })
        integer(A.A_CollisionResponse_superDamage, { it.superDamage }, { o, v -> o.superDamage = v })
        val beforeGet = LuaReg.objGet[A.A_CollisionResponse_point]!!
        val beforeSet = LuaReg.objSet[A.A_CollisionResponse_point]!!
        LuaReg.obj(A.A_CollisionResponse_point, ObjGet { o ->
            if (o !is HsWeaponHooks.CollisionView) return@ObjGet beforeGet.get(o)
            val value = o.response.point
            LuaViews.cached(o.response, A.A_CollisionResponse_point) {
                LuaPointfMember(FGet { value.xf }, FSet { value.xf = it }, FGet { value.yf }, FSet { value.yf = it })
            }
        }, ObjSet { o, v ->
            if (o is HsWeaponHooks.CollisionView) o.response.point.set(point(v)) else beforeSet.set(o, v)
        })
    }

    private fun projectileMembers() {
        LuaReg.obj(A.A_ProjectileFactory_blueprint, ObjGet { (it as AbstractWeaponInstance).type })
        // Preserve the existing Pointf value/struct adapters; these additionally handle pointers into gameplay.
        val xg = LuaReg.numGet[A.A_Pointf_x]!!; val xs = LuaReg.numSet[A.A_Pointf_x]!!
        val yg = LuaReg.numGet[A.A_Pointf_y]!!; val ys = LuaReg.numSet[A.A_Pointf_y]!!
        LuaReg.num(A.A_Pointf_x, NumGet { if (it is LuaPointfMember) it.x.toDouble() else xg.get(it) },
            NumSet { o, v -> if (o is LuaPointfMember) o.x = v.toFloat() else xs.set(o, v) })
        LuaReg.num(A.A_Pointf_y, NumGet { if (it is LuaPointfMember) it.y.toDouble() else yg.get(it) },
            NumSet { o, v -> if (o is LuaPointfMember) o.y = v.toFloat() else ys.set(o, v) })

        fun state(o: Any?) = HsWeaponsLua.extOf(o) ?: throw LuaError("expected native Projectile")
        fun p(o: Any?) = o as? AbstractProjectile ?: throw LuaError("expected moving Projectile")
        fun vector(a: Int, select: (Any) -> MutFPoint) {
            val previous = LuaReg.objGet[a]
            LuaReg.obj(a, ObjGet { o ->
                if (o !is AbstractProjectile) return@ObjGet previous?.get(o)
                LuaViews.cached(o, a) { owner ->
                    LuaPointfMember(FGet { select(owner).xf }, FSet { select(owner).xf = it },
                        FGet { select(owner).yf }, FSet { select(owner).yf = it })
                }
            }, ObjSet { o, value -> select(o!!).set(point(value)) })
        }
        vector(A.A_Projectile_position) { p(it).position as MutFPoint }
        vector(A.A_Projectile_last_position) { state(it).lastPosition }
        vector(A.A_Projectile_speed) { state(it).frameSpeed }
        vector(A.A_Projectile_target) { p(it).targetPos as MutFPoint }
        LuaReg.obj(A.A_Projectile_damage, ObjGet { state(it).damage }, ObjSet { o, v ->
            state(o).damage.copyFrom(v as? Damage ?: throw LuaError("expected Damage"))
        })
        LuaReg.num(A.A_Projectile_speed_magnitude, NumGet { state(it).speedMagnitude.toDouble() },
            NumSet { o, v -> state(o).speedMagnitude = v.toFloat() })
        LuaReg.num(A.A_Projectile_lifespan, NumGet { state(it).lifespan.toDouble() },
            NumSet { o, v -> state(o).lifespan = v.toFloat() })
        LuaReg.num(A.A_Projectile_selfId, NumGet { state(it).selfId.toDouble() })
        LuaReg.bool(A.A_Projectile_dead, { p(it).hsDead }, { o, v -> p(o).hsDead = v })
        LuaReg.obj(A.A_Projectile_death_animation, ObjGet { LuaProjectileAnimation.view(p(it).deathAnimation) })
        LuaReg.bool(A.A_Projectile_startedDeath, { state(it).startedDeath }, { o, v -> state(o).startedDeath = v })
        LuaReg.bool(A.A_Projectile_passedTarget, { p(it).nativePassedTarget }, { o, v -> p(o).nativePassedTarget = v })
        LuaReg.bool(A.A_Projectile_hitTarget, { state(it).hitTarget }, { o, v -> state(o).hitTarget = v })
        LuaReg.bool(A.A_Projectile_missed, { state(it).missed }, { o, v -> state(o).missed = v })
        LuaReg.num(A.A_Projectile_heading, NumGet { o ->
            if (o is BeamProjectile) o.nativeHeading.toDouble() else (Math.toDegrees(p(o).rotation.toDouble()) + 360.0) % 360.0
        }, NumSet { o, v -> if (o is BeamProjectile) o.setBeamHeading(v.toFloat()) else p(o).hsSetHeading(v.toFloat()) })
        LuaReg.num(A.A_Projectile_destinationSpace, NumGet { p(it).hsDestinationSpaceId.toDouble() },
            NumSet { o, v -> p(o).hsDestinationSpaceId = v.toInt() })
        LuaReg.num(A.A_Projectile_targetId, NumGet { p(it).hsTargetId.toDouble() },
            NumSet { o, v -> p(o).hsTargetId = v.toInt() })
        LuaReg.num(A.A_Projectile_entryAngle, NumGet { state(it).entryAngle.toDouble() },
            NumSet { o, v -> state(o).entryAngle = v.toFloat() })
        val oldOwner = LuaReg.numGet[A.A_Projectile_ownerId]!!
        LuaReg.num(A.A_Projectile_ownerId, NumGet { if (it is AbstractProjectile) it.hsOwnerId.toDouble() else oldOwner.get(it) },
            NumSet { o, v -> p(o).hsOwnerId = v.toInt() })
        val oldSpace = LuaReg.numGet[A.A_Projectile_currentSpace]!!
        LuaReg.num(A.A_Projectile_currentSpace, NumGet {
            if (it is AbstractProjectile) it.hsCurrentSpaceId.toDouble() else oldSpace.get(it)
        }, NumSet { o, v ->
            p(o).hsCurrentSpace = LuaAdapters.shipManager(v.toInt()); p(o).hsCurrentSpaceId = v.toInt()
            state(o).spaceKnown = true
        })
        LuaReg.obj(A.A_Projectile_Extend_orig, ObjGet { (it as HsWeaponsLua.ExtendView).ext.orig })
        LuaReg.obj(A.A_Projectile_Extend_missedDrones, ObjGet { o ->
            val ext = (o as HsWeaponsLua.ExtendView).ext
            LuaViews.cached(ext, A.A_Projectile_Extend_missedDrones) {
                ListView(A.T_vector_int, 0) { ext.missedDrones }
            }
        })
        LuaReg.method(A.M_Projectile_GetType, LuaMethod {
            val obj = arg.obj(1)
            // The base constructor's CONSTRUCT_PROJECTILE sees the base vtable: GetType 0.
            ret.num(if (obj is AbstractProjectile && obj.hsExt?.constructing == true) 0 else when (obj) {
                is AbstractProjectile -> obj.nativeType
                else -> 0
            })
        })
        LuaReg.method(A.M_Projectile_GetOwnerId, LuaMethod { ret.num(p(arg.obj(1)).hsOwnerId) })
        LuaReg.method(A.M_Projectile_GetSpaceId, LuaMethod { ret.num(p(arg.obj(1)).hsCurrentSpaceId) })
        LuaReg.method(A.M_Projectile_GetSelfId, LuaMethod { ret.num(state(arg.obj(1)).selfId) })
        LuaReg.method(A.M_Projectile_SetDamage, LuaMethod {
            state(arg.obj(1)).damage.copyFrom(arg.obj(2) as Damage); 0
        })
        LuaReg.method(A.M_Projectile_Dead, LuaMethod { ret.bool(p(arg.obj(1)).nativeDead(LuaFps.speedFactor * .0625f)) })
        LuaReg.method(A.M_Projectile_StartedDeath, LuaMethod { ret.bool(state(arg.obj(1)).startedDeath) })
        // The native base ValidTarget only tests startedDeath, independently of dead and tracker.running.
        LuaReg.method(A.M_Projectile_ValidTarget, LuaMethod { ret.bool(!state(arg.obj(1)).startedDeath) })
        LuaReg.method(A.M_Projectile_AtTarget, LuaMethod { ret.bool(p(arg.obj(1)).nativeAtTarget(LuaFps.speedFactor * .0625f)) })
        LuaReg.method(A.M_Projectile_SetTarget, LuaMethod {
            val body = p(arg.obj(1)); body.nativeSetTarget(point(arg.obj(2)))
            if (body is BeamProjectile && body.hsCurrentSpaceId == body.hsDestinationSpaceId) body.computeBeamHeading()
            0
        })
        LuaReg.method(A.M_Projectile_SetDestinationSpace, LuaMethod { p(arg.obj(1)).hsDestinationSpaceId = arg.int(2); 0 })
        LuaReg.method(A.M_Projectile_EnterDestinationSpace, LuaMethod { p(arg.obj(1)).nativeEnterDestination(); 0 })
        LuaReg.method(A.M_Projectile_Initialize__SWIG_0, LuaMethod {
            val projectile = p(arg.obj(1)); val blueprint = arg.obj(2) as AbstractWeaponBlueprint
            if (projectile is BeamProjectile) projectile.initialize(blueprint)
            else projectile.hsInitialize(blueprint, if (projectile is AbstractWeaponProjectile) projectile.defaultSpeed else 0)
            0
        })
        LuaReg.method(A.M_Projectile_Initialize__SWIG_1, LuaMethod {
            val projectile = p(arg.obj(1)); val name = arg.str(2) ?: ""
            val blueprint = LuaAdapters.inGame()?.blueprintManager?.getOrNull(name) as? AbstractWeaponBlueprint
                ?: throw LuaError("unknown weapon blueprint $name")
            if (projectile is BeamProjectile) projectile.initialize(blueprint)
            else projectile.hsInitialize(blueprint, if (projectile is AbstractWeaponProjectile) projectile.defaultSpeed else 0)
            0
        })
        LuaReg.method(A.M_Projectile_ForceRenderLayer, LuaMethod {
            ret.num(if (state(arg.obj(1)).damage.hsCrystalShard) 1 else -1)
        })
        LuaReg.method(A.M_Projectile_CollisionMoving, LuaMethod {
            val response = p(arg.obj(1)).nativeCollisionMoving(point(arg.obj(3)), (arg.obj(4) as Damage).copy())
            ret.obj(HsWeaponHooks.collisionView(response), A.T_CollisionResponse)
        })
        LuaReg.method(A.M_Projectile_CollisionCheck, LuaMethod {
            val projectile = p(arg.obj(1))
            val value = arg.obj(2)
            when (val other = if (value is LuaCollideableMember) value.owner else value) {
                is AbstractProjectile -> ProjectileRuntime.collisionCheck(projectile, other)
                else -> HsDrones.collisionBody(other)?.let { ProjectileRuntime.collisionCheck(projectile, it) }
                    ?: throw LuaError("CollisionCheck body not implemented: ${other?.javaClass?.simpleName}")
            }
            0
        })
        LuaReg.method(A.M_Projectile_GetSpeed, LuaMethod {
            val value = state(arg.obj(1)).frameSpeed; ret.obj(PointfValue(value.xf, value.yf), A.T_Pointf)
        })
        LuaReg.method(A.M_Projectile_GetWorldCenterPoint, LuaMethod {
            val value = p(arg.obj(1)).position; ret.obj(PointfValue(value.xf, value.yf), A.T_Pointf)
        })
        LuaReg.method(A.M_Projectile_ComputeHeading, LuaMethod {
            val body = p(arg.obj(1)); if (body is BeamProjectile) body.computeBeamHeading() else body.hsComputeHeading(); 0
        })
        LuaReg.method(A.M_Projectile_OnUpdate, LuaMethod {
            p(arg.obj(1)).hsOnUpdate(LuaFps.speedFactor * .0625f); 0
        })
        LuaReg.method(A.M_SpaceManager_UpdateProjectile, LuaMethod {
            val projectile = p(arg.obj(2))
            val space = projectile.hsCurrentSpace ?: LuaAdapters.inGame()?.player ?: throw LuaError("no game")
            ProjectileRuntime.update(projectile, LuaFps.speedFactor * .0625f, space); 0
        })
        LuaReg.method(A.M_SpaceManager_CreateLaserBlast, LuaMethod {
            val blueprint = arg.obj(2) as AbstractWeaponBlueprint
            val position = point(arg.obj(3))
            val target = point(arg.obj(6))
            // A space or destination with no ship (oracle hw-clb): the shot exists with those numbers, hits nothing and is gone
            // at once when already in its destination space, else soon after it leaves.
            val spaceShip = LuaAdapters.shipManager(arg.int(4))
            val destinationShip = LuaAdapters.shipManager(arg.int(7))
            val space = spaceShip ?: LuaAdapters.inGame()?.player ?: throw LuaError("no game")
            val destination = destinationShip ?: space
            val context = HsProjectileConstruction.Context(position, target, space, arg.int(5))
            // A laser weapon's blast is its LaserProjectile, which a save writes with its target room so that Continue
            // reloads it (HS keeps a script's laser blast across a save: oracle hs-gaps proj1 / proj2)
            val projectile = HsProjectileConstruction.with(context) {
                (blueprint as? xyz.znix.xftl.weapons.LaserBlueprint)?.LaserProjectile(destination.rooms.first())
                    ?: LaserBlastProjectile(blueprint, destination.rooms.first())
            }
            projectile.setInitialPath(position, target)
            if (space !== destination) projectile.hsSetHeading(arg.float(8))
            projectile.hsCurrentSpace = space
            space.projectiles += projectile
            if (spaceShip == null || destinationShip == null) {
                LuaStored.put(projectile, A.A_Projectile_currentSpace, arg.int(4).toDouble())
                LuaStored.put(projectile, A.A_Projectile_destinationSpace, arg.int(7).toDouble())
                LuaStored.put(projectile, A.A_Projectile_ownerId, arg.int(5).toDouble())
                var frames = if (arg.int(4) == arg.int(7)) 1 else 30
                fun tick() { if (--frames <= 0) space.projectiles -= projectile else LuaStored.onNextFrame { tick() } }
                LuaStored.onNextFrame { tick() }
            }
            ret.obj(projectile, A.T_LaserBlast)
        })
        // SWIG assigns a distinct method id to each declared override. These implementations dispatch on the
        // actual gameplay body, so the subclass overrides use the same virtual operation, not an empty shim.
        for (id in intArrayOf(A.M_Asteroid_GetType, A.M_BeamWeapon_GetType, A.M_BombProjectile_GetType,
            A.M_LaserBlast_GetType, A.M_Missile_GetType, A.M_PDSFire_GetType))
            LuaReg.methods[id] = LuaReg.methods[A.M_Projectile_GetType]
        LuaReg.methods[A.M_LaserBlast_ComputeHeading] = LuaReg.methods[A.M_Projectile_ComputeHeading]
        LuaAdaptersBeam.install()
    }
}
