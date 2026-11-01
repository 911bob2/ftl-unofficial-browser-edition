// Ported from HS GPL-3.0-or-later 1b05a07 CustomDamage.cpp, with native 1.6.9
// SpaceManager::UpdateProjectile 0x41a190 and WorldManager::OnLoop 0x5f6520.
// Shared vanilla order verified in 1.6.14 WorldManager 0x61b930 / SpaceManager 0x41f3c0.
package xyz.znix.xftl.weapons

import xyz.znix.xftl.hs.weapons.*

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.weapons.AbstractProjectile
import xyz.znix.xftl.weapons.IProjectile
import xyz.znix.xftl.weapons.ProjectileContact

/** Native SpaceManager order is shared across both ship spaces. Direct Lua UpdateProjectile calls use the
 * same operation as world simulation; direct Projectile.OnUpdate uses movement only. */
object ProjectileRuntime {
    fun collisionCheck(projectile: AbstractWeaponProjectile, target: Ship) {
        val shipId = HsWeapons.shipId(target)
        if (projectile.deathAnimationRunning || projectile.hsOwnerId == shipId ||
            projectile.hsCurrentSpaceId != shipId || projectile.hsExt?.selfId == shipId) return
        val ext = projectile.hsExt
        ext?.sourceShipId = projectile.hsOwnerId
        ext?.damage?.hsSelfId = ext?.selfId ?: -1
        val damage = projectile.computeDamage()
        if (ext == null) damage.hsSelfId = projectile.worldOrder
        HsDamage.with(ext, projectile) {
            val response = ShieldCollisionRules.moving(target, ext?.lastPosition ?: projectile.nativeLastPosition,
                projectile.position, damage) {
                projectile.nativeShieldDodged(target)
            }
            projectile.applyNativeShieldResponse(response, damage)
        }
    }

    fun collisionCheck(projectile: AbstractProjectile, target: HsDroneCollisionBody) {
        if (projectile is BeamProjectile) return
        if (projectile.deathAnimationRunning || projectile.hsOwnerId == HsWeapons.shipId(target.collisionOwner) ||
            projectile.hsCurrentSpaceId != target.collisionSpaceId || projectile.hsExt?.selfId == target.collisionSelfId) return
        val ext = projectile.hsExt
        ext?.sourceShipId = projectile.hsOwnerId
        ext?.damage?.hsSelfId = ext?.selfId ?: -1
        val damage = if (projectile is AbstractWeaponProjectile) projectile.computeDamage()
            else ext?.damage?.copy() ?: Damage.regular(1)
        HsDamage.with(ext, projectile) {
            projectile.nativeApplyCollision(HsDrones.collisionMoving(target, projectile.position, damage), damage)
        }
    }

    fun collisionCheck(projectile: AbstractProjectile, target: AbstractProjectile) {
        if (projectile is BeamProjectile) return
        if (projectile === target || projectile.deathAnimationRunning ||
            projectile.hsOwnerId == target.hsOwnerId || projectile.hsCurrentSpaceId != target.hsCurrentSpaceId ||
            (projectile.hsExt != null && projectile.hsExt!!.selfId == target.hsExt?.selfId)) return
        val ext = projectile.hsExt
        ext?.sourceShipId = projectile.hsOwnerId
        ext?.damage?.hsSelfId = ext?.selfId ?: -1
        val damage = if (projectile is AbstractWeaponProjectile) projectile.computeDamage()
            else ext?.damage?.copy() ?: Damage.regular(1)
        HsDamage.with(ext, projectile) {
            projectile.nativeApplyCollision(target.nativeCollisionMoving(projectile.position, damage), damage)
        }
    }
    fun nativeMotion(projectile: AbstractProjectile): Boolean = projectile.nativeBody ||
        projectile is xyz.znix.xftl.weapons.LaserBlastProjectile ||
        projectile is xyz.znix.xftl.weapons.MissileBody || projectile is BeamProjectile
    fun handlesContacts(projectile: AbstractProjectile): Boolean = nativeMotion(projectile) ||
        (xyz.znix.xftl.hs.HS.active && projectile.hsNativeProjectile)
    private var owner: InGameState? = null
    private val order = ArrayList<AbstractProjectile>()

    private fun sync(game: InGameState) {
        if (owner !== game) { owner = game; order.clear() }
        val live = (game.player.projectiles + (game.enemy?.projectiles ?: emptyList()))
            .filterIsInstance<AbstractProjectile>().filter { it.hsNativeProjectile }
        order.removeAll { it !in live }
        for (p in live.sortedBy { it.worldOrder }) if (p !in order) {
            if (!xyz.znix.xftl.hs.HS.active || p.hsExt?.spaceKnown != true) {
                p.hsCurrentSpace = if (p in game.player.projectiles) game.player else game.enemy
                p.hsExt?.spaceKnown = true
            }
            order += p
        }
    }

    /**
     * SpaceManager::OnRenderProjectiles (1.6.14 0x41a8b0), called by CompleteShip::OnRender with layer 2 before
     * ShipManager::OnRender and layer 1 after it. A body whose ForceRenderLayer is not -1 draws only in that layer
     * (PDSFire: 2 until it passes its target, then 1; HS crystal shards: 1). Otherwise the owner's departing shot
     * draws under the ship and everything else over it, which [IProjectile.drawUnderShip] tracks.
     */
    @JvmStatic
    fun rendersInLayer(projectile: IProjectile, layer: Int): Boolean {
        val forced = (projectile as? AbstractProjectile)?.nativeForceRenderLayer() ?: -1
        if (forced != -1) return forced == layer
        // A Hyperspace script's body owned by the space it is aimed at draws in both passes (see [nativeLayer]).
        if (xyz.znix.xftl.hs.HS.active && projectile is AbstractProjectile && projectile.hsOwnerId == projectile.hsDestinationSpaceId &&
            projectile.hsCurrentSpaceId == projectile.hsOwnerId) return true
        return projectile.drawUnderShip == (layer == 2)
    }

    /**
     * OnRenderProjectiles (1.6.14 0x41a8b0) per projectile, before Projectile::OnRender's own currentSpace test
     * (OnRenderSpecific draws only for spaceId == currentSpace, LaserBlast 0x456980): with ForceRenderLayer [forced]
     * not -1 only that layer; otherwise layer 2 when the owner is [space] (a departing shot), and layer 1 when the
     * owner is another space or the destination is [space] - so a body owned by the space it is aimed at draws in
     * both passes. Layer 0 draws everything unforced. Measured by the native bodies capture (render matrix).
     */
    @JvmStatic
    fun nativeLayer(owner: Int, destination: Int, forced: Int, space: Int, layer: Int): Boolean {
        val unforced = when (layer) {
            1 -> owner != space || destination == space
            2 -> owner == space
            0 -> true
            else -> false
        }
        return if (unforced) forced == -1 || forced == layer else forced != -1 && forced == layer
    }

    /**
     * OnRenderProjectiles (0x41a8b0) for space 0, layer 2, before the projectiles: HS's CreatePDSFire puffs (its
     * hook runs before the native function), then UpdatePDSFire's smokes.
     */
    @JvmStatic
    fun renderSmoke(g: xyz.znix.xftl.rendering.Graphics, space: Ship) {
        if (space !== space.sys.player) return
        if (xyz.znix.xftl.hs.HS.active) HsPdsSmoke.render(g, space)
        val beacon: xyz.znix.xftl.sector.Beacon = space.sys.currentBeacon ?: return
        beacon.getEnvironment(space.sys).layers.firstNotNullOfOrNull { it as? xyz.znix.xftl.environment.AsbEnvironment }?.renderSmoke(g)
    }

    /**
     * Projectile::OnRender (1.6.14 0x45d290), as OnRenderProjectiles calls it: flashTracker.Update() (time 2,
     * looping, started by the Projectile constructor 0x4598e0) by SpeedFactor / 16, then OnRenderSpecific. Only HS
     * scripts can see the tracker (Projectile.flashTracker), so it runs only under HS.
     */
    @JvmStatic
    fun render(projectile: IProjectile, g: xyz.znix.xftl.rendering.Graphics, space: Ship) {
        if (xyz.znix.xftl.hs.HS.active && projectile is AbstractProjectile)
            projectile.nativeFields.flashTracker.advance(xyz.znix.xftl.hs.lua.LuaFps.speedFactor * .0625f)
        projectile.render(g, space)
    }

    fun added(projectile: AbstractProjectile, space: Ship) {
        sync(space.sys)
        projectile.hsCurrentSpace = space
        if (projectile !in space.projectiles) space.projectiles += projectile
        if (projectile !in order) order += projectile
    }

    @JvmStatic
    fun advanceFrame(game: InGameState, dt: Float) {
        if (dt <= 0f) return
        sync(game)
        // Native SpaceManager::OnLoop walks the global vector forwards, before either ShipManager loop.
        var index = 0
        while (index < order.size) {
            val p = order[index]
            index++
            if (!nativeMotion(p)) {
                // Other virtual movement implementations retain their own update, here or in their ship's update.
                if (p.updatesInProjectileLoop) {
                    val space = if (p in game.player.projectiles) game.player else game.enemy?.takeIf { p in it.projectiles }
                    space?.let { p.update(dt, it) }
                }
                continue
            }
            if (p.nativeDead(dt)) {
                game.player.projectiles.remove(p); game.enemy?.projectiles?.remove(p); order.remove(p)
                // Native increments even after erase, so the following element waits until the next frame.
                continue
            }
            val space = p.hsCurrentSpace ?: game.player // Numeric scripted spaces still update and collect Dead bodies.
            update(p, dt, p.nativeCheckBounds(space))
        }
    }

    fun update(projectile: AbstractProjectile, dt: Float, currentSpace: Ship) {
        if (dt <= 0f) return // save restoration's zero-time updates do not advance simulation
        if (!xyz.znix.xftl.hs.HS.active) {
            projectile.nativeUpdate(dt, currentSpace)
            contacts(projectile, currentSpace)
            return
        }
        val ext = projectile.hsExt ?: return
        ext.sourceShipId = projectile.hsOwnerId
        HsDamage.with(ext, projectile) {
            val preempt = HsWeaponHooks.projectileBefore(A.EV_PROJECTILE_PRE, projectile)
            if (!preempt) {
                projectile.hsOnUpdate(dt)
                if (!projectile.deathAnimationRunning) {
                    val space = projectile.hsCurrentSpace ?: currentSpace
                    // UpdateProjectile offers every ship to CollisionCheck; the base body ignores other spaces, but
                    // BombProjectile's override only tests targetId, so a bomb still in its launch space arrives
                    // (the HS oracle's CreateBomb is in space 0 after its first UpdateProjectile).
                    val receiver = if (projectile is BombProjectile) projectile.targetShip ?: space else space
                    if (projectile is BombProjectile || projectile.hsCurrentSpaceId == HsWeapons.shipId(receiver))
                        projectile.hsResolveShipCollision(receiver, dt)
                    contacts(projectile, space)
                }
            }
            HsWeaponHooks.projectileAfter(A.EV_PROJECTILE_POST, projectile, preempt)
        }
    }

    private fun contacts(projectile: AbstractProjectile, space: Ship) {
        if (projectile is BeamProjectile) return // Native BeamWeapon::CollisionCheck only accepts its moving ship target.
        if (!projectile.collisionsEnabled || projectile.deathAnimationRunning) return
        if (xyz.znix.xftl.hs.HS.active && projectile.hsCurrentSpaceId != HsWeapons.shipId(space)) return
        // CollisionMoving tests the incoming finish point, within squared distance 100 (native 0x44ebf0).
        for (drone in space.externalDrones.toList()) {
            if (projectile.deathAnimationRunning) break
            if (!near(drone.flightController.position, projectile.position)) continue
            if (xyz.znix.xftl.hs.HS.active) {
                collisionCheck(projectile, drone)
                continue
            }
            if (!drone.canCollideWith(projectile)) continue
            projectile.startDeathAnimation()
            drone.hitProjectile(projectile)
        }
        if (projectile.deathAnimationRunning) return
        for (other in space.projectiles.toList()) {
            if (projectile.deathAnimationRunning) break
            if (other === projectile) continue
            if (xyz.znix.xftl.hs.HS.active && other.hsDroneCollisionBody != null) {
                collisionCheck(projectile, other.hsDroneCollisionBody!!)
                continue
            }
            if (!other.collisionsEnabled) continue
            if (other is AbstractProjectile && other.hsNativeProjectile) {
                if (other.deathAnimationRunning || other.hsOwnerId == projectile.hsOwnerId ||
                    (other.hsExt != null && other.hsExt!!.selfId == projectile.hsExt?.selfId)) continue
            } else if (other.antiDroneExemption === projectile.antiDroneExemption) continue
            if (!near(other.position, projectile.position)) continue
            if (other is AbstractProjectile && other.hsNativeProjectile) {
                collisionCheck(projectile, other)
                continue
            }
            val incoming = projectile.onProjectileContact(other)
            val target = other.onProjectileContact(projectile)
            if (incoming == ProjectileContact.IGNORED || target == ProjectileContact.IGNORED) continue
            if (target == ProjectileContact.DESTROYED) {
                other.hitOtherProjectile(space)
                if (other is AbstractProjectile) other.hsDead = true else space.projectiles.remove(other)
            }
            if (incoming == ProjectileContact.DESTROYED) {
                projectile.hitOtherProjectile(space)
                projectile.hsDead = true
            }
        }
    }

    private fun near(a: xyz.znix.xftl.math.FPoint, b: xyz.znix.xftl.math.FPoint): Boolean {
        return IProjectile.endpointContact(a, b)
    }
}
