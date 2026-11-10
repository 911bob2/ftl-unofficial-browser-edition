// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrystalShard.cpp
// (ShipManager::CheckCrystalAugment hook, ShipManager_CheckCustomCrystalAugment), src/game/Ship/CustomAugments.cpp
// (<crystalShard weapon value|chance stackable>)
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.events.HsGnuStringMap
import xyz.znix.xftl.hs.ship.HsAugments
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponProjectile
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.BombBlueprint
import xyz.znix.xftl.weapons.FlakBlueprint
import xyz.znix.xftl.weapons.LaserBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint

/**
 * Custom augments' crystal shards. After the vanilla Crystal Vengeance roll, every augment the ship carries adds its
 * `<crystalShard>` entries per weapon: `stackable="false"` (0) keeps the highest chance, `"true"` (1) adds
 * count x chance, `"independent"` (2) rolls once per copy straight away. Then each weapon in the list rolls its
 * summed chance (an entry made only by independent shards still rolls, at 0).
 *
 * HS walks unordered_maps (the augment list and the shard list): [HsGnuStringMap] keeps libstdc++'s order.
 */
object HsCrystalShards {
    class Shard(@JvmField val weapon: String, @JvmField val chance: Float, @JvmField val stacking: Int)

    private val cache = HashMap<HsAugments.AugmentDefinition, List<Shard>>()

    private fun shards(def: HsAugments.AugmentDefinition): List<Shard> = cache.getOrPut(def) {
        def.crystalShards.map { n ->
            var chance = 0f
            n.getAttributeValue("value")?.let { chance = it.toFloat() }
            n.getAttributeValue("chance")?.let { chance = it.toFloat() }
            val stacking = when (val m = n.getAttributeValue("stackable")) {
                null -> 0
                "independent" -> 2
                else -> if (HsConfig.bool(m)) 1 else 0
            }
            Shard(n.getAttributeValue("weapon") ?: "", chance, stacking)
        }
    }

    /** ShipManager::CheckCrystalAugment after super: [ship] was hit at [pos]; [target] is its current target. */
    @JvmStatic
    fun check(ship: Ship, target: Ship, pos: IPoint) {
        val list = HsGnuStringMap<FloatArray>()
        // CustomAugmentManager::GetShipAugments: the std::map of the augments (hidden ones as "HIDDEN <name>", sorted
        // with the prefix) walked into an unordered_map; the shard list is a second one, so both walk in hash order.
        val raw = java.util.TreeMap<String, Int>()
        for (a in ship.augments) raw[a.name] = (raw[a.name] ?: 0) + 1
        ship.hs?.hiddenAugs?.let { for ((n, c) in it) raw["HIDDEN $n"] = (raw["HIDDEN $n"] ?: 0) + c }
        val augs = HsGnuStringMap<IntArray>()
        for ((key, count) in raw) {
            val name = key.removePrefix("HIDDEN ")
            augs.emplace(name, IntArray(1))
            augs[name]!![0] += count
        }
        for (node in augs.entries()) {
            val count = node.value[0]
            if (count <= 0) continue
            val def = HsAugments.definition(node.key) ?: continue
            for (shard in shards(def)) {
                list.emplace(shard.weapon, FloatArray(2))
                val pair = list[shard.weapon]!!
                when (shard.stacking) {
                    0 -> pair[0] = maxOf(pair[0], shard.chance)
                    1 -> pair[1] += count * shard.chance
                    2 -> repeat(count) { fire(ship, target, shard.weapon, shard.chance, pos) }
                }
            }
        }
        for (node in list.entries()) fire(ship, target, node.key, node.value[0] + node.value[1], pos)
    }

    /**
     * ShipManager_CheckCustomCrystalAugment: `random32() % 100 < chance * 100` launches the weapon's shots from [pos]
     * (a beam once), marked as crystal shards and owned by [ship] (crystalShardFix) or by no one: a laser or missile
     * at a random room on a random heading, a beam from a random room centre towards another, a bomb in every room in
     * a shuffled order, a flak burst at a random room. Each shot then plays a random launch sound.
     */
    internal fun fire(ship: Ship, target: Ship, weapon: String, chance: Float, pos: IPoint) {
        if (HsWeapons.r32() % 100 >= chance * 100f) return
        val bp = ship.sys.blueprintManager.getOrNull(weapon) as? AbstractWeaponBlueprint ?: return
        if (target.rooms.isEmpty()) return
        val neutral = !HS.cfg().crystalShardFix
        fun mark(shot: AbstractWeaponProjectile) {
            shot.hsCrystalShard = true
            shot.hsExt?.damage?.hsCrystalShard = true
            if (neutral) shot.hsNeutral = true
        }
        fun randomRoom() = target.rooms[HsWeapons.r32() % target.rooms.size] // GetRandomRoomCenter
        val bombs = ArrayList<xyz.znix.xftl.layout.Room>()
        var bombIndex = 0
        repeat(if (bp is BeamBlueprint) 1 else bp.shots) {
            when (bp) {
                is LaserBlueprint, is MissileBlueprint -> {
                    val room = randomRoom()
                    val heading = (HsWeapons.r32() % 360).toFloat()
                    val shot: AbstractWeaponProjectile = if (bp is LaserBlueprint) bp.LaserProjectile(room) else (bp as MissileBlueprint).MissileProjectile(room)
                    mark(shot)
                    // LaserBlast / Missile(pos, ship, targetId, the room's centre[, heading]): the target is the room's
                    // centre from the start; the shot leaves on its heading and, in the target's space, flies at it.
                    shot.setInitialPath(pos, room.pixelCentre)
                    shot.hsSetHeading(heading)
                    ship.projectiles.add(shot)
                }
                is BeamBlueprint -> xyz.znix.xftl.hs.ship.HsBoss.fireCrystalBeam(ship, target, bp, randomRoom())
                is BombBlueprint -> {
                    if (bombIndex >= bombs.size) {
                        bombs.clear(); bombs.addAll(target.rooms)
                        for (k in 1 until bombs.size) java.util.Collections.swap(bombs, k, HsWeapons.r32() % (k + 1))
                        bombIndex = 0
                    }
                    val shot = (bp.buildInstance(ship) as BombBlueprint.BombInstance).fireBarrage(bombs[bombIndex++])
                    (shot as? AbstractWeaponProjectile)?.let { mark(it) }
                }
                is FlakBlueprint -> {
                    val room = randomRoom()
                    val heading = (HsWeapons.r32() % 360).toFloat()
                    val entryAngle = (HsWeapons.r32() % 360).toFloat()
                    val before = ship.projectiles.size
                    (bp.buildInstance(ship) as FlakBlueprint.FlakInstance).hsBarrageVolley(room, pos, heading, entryAngle)
                    for (i in before until ship.projectiles.size) (ship.projectiles[i] as? AbstractWeaponProjectile)?.let { mark(it) }
                }
                else -> {
                    HS.unsupported("crystalShard", weapon, bp.javaClass.simpleName)
                    return
                }
            }
            bp.launchSounds?.sounds?.takeIf { it.isNotEmpty() }?.let { it[HsWeapons.r32() % it.size]?.play() }
        }
    }
}
