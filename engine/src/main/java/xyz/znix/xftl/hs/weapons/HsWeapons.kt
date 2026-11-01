// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomWeapons.cpp,
// src/game/Gameplay/AugmentValues.cpp, src/game/Gameplay/CustomDrones.cpp, src/game/Ship/CustomShips.cpp
package xyz.znix.xftl.hs.weapons

import ftl.browser.BrowserRandom
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsCapability
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.systems.SelectedTarget
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint

/**
 * Hyperspace's weapon rules. Every entry point is only called from `if (HS.active)` call sites in the engine's
 * weapon code; the order relative to the engine's equivalent of the hooked FTL function is noted on each.
 *
 * HS hook priorities: zhl installs hooks in descending priority, each wrapping the previous, so the lowest priority
 * runs first and a priority-9999 "rewrite" sits innermost, in place of the original function.
 */
object HsWeapons {
    /** ShipObject::iShipId of [ship]: 0 the player, 1 the enemy, -1 none (hazards). */
    @JvmStatic
    fun shipId(ship: Ship?): Int = when {
        ship == null -> -1
        ship.isPlayerShip -> 0
        else -> 1
    }

    /** FTL's random32() (31 bits) on the combat stream: `random32() % n` is `r32() % n`. */
    @JvmStatic
    fun r32(): Int = BrowserRandom.nextBits(31)

    /**
     * Globals::GetNextSpaceId (0x5ca650; HS SpaceId.cpp): one counter for crew, drones and asteroids, the one the
     * crew code keeps (HsCrew.nextSelfId, reset and saved there).
     */
    @JvmStatic fun nextSpaceId(): Int = xyz.znix.xftl.hs.crew.HsCrew.nextSelfId()

    /** The weapon tags and rules this workstream implements (taken off the capability report). */
    @JvmStatic
    fun registerImplemented() {
        HsCapability.implement(
            "accuracyMod", "noSysDamage", "noPersDamage", "freeMissileChance", "shotLimit", "iconReplace",
            "iconScale", "fireTime", "ionBeamFix", "invisibleBeam", "projectile@blueprint", "angularRadius",
            "descriptionOverride", "tooltipOverride", "droneAccuracyMod", "statBoosts", "statBoostChance", "roomStatBoosts",
            "roomStatBoostChance", "spawnCrew", "crewSpawnChance", "erosion", "erosionChance", "simultaneousFire", "crystalShard", "drones", "crewBlueprint",
        )
    }

    // ---------------------------------------------------------------- content

    /**
     * The weapons' stat boosts and crew spawns (ProcessWeaponBlueprint's ParseStatBoostNode / ParseCrewSpawn), parsed
     * once the HS crew content is loaded (after hyperspace.xml's crew and augments, as HS parses hyperspace.xml before
     * the blueprints): blueprint order, each weapon's nodes in document order.
     */
    @JvmStatic
    fun loadContent(blueprints: xyz.znix.xftl.BlueprintManager) {
        if (!HS.active) return
        HsDrones.reset()
        val seen = java.util.IdentityHashMap<HsDamageDef, Boolean>()
        for (bp in blueprints.blueprints.values) {
            val def = (bp as? AbstractWeaponBlueprint)?.hs?.damage ?: continue
            if (def === HsDamageDef.DEFAULT || seen.put(def, true) != null) continue
            val stat = ArrayList<xyz.znix.xftl.hs.stat.StatBoostDefinition>()
            val room = ArrayList<xyz.znix.xftl.hs.stat.StatBoostDefinition>()
            val spawns = ArrayList<xyz.znix.xftl.hs.crew.CrewSpawn>()
            for (n in def.content) when (n.kind) {
                HsDamageDef.STAT -> stat += xyz.znix.xftl.hs.stat.StatBoostDefs.parse(n.element,
                    xyz.znix.xftl.hs.stat.StatBoostDefinition.BoostSource.AUGMENT, false)
                HsDamageDef.ROOM_STAT -> room += xyz.znix.xftl.hs.stat.StatBoostDefs.parse(n.element,
                    xyz.znix.xftl.hs.stat.StatBoostDefinition.BoostSource.AUGMENT, true)
                HsDamageDef.SPAWN -> spawns += xyz.znix.xftl.hs.crew.CustomCrewManager.parseCrewSpawn(n.element, false)
                HsDamageDef.SPAWN_DROPPED -> xyz.znix.xftl.hs.crew.CustomCrewManager.parseCrewSpawn(n.element, false)
            }
            def.statBoostDefs = stat
            def.roomStatBoostDefs = room
            def.crewSpawnDefs = spawns
        }
    }

    // ---------------------------------------------------------------- missiles

    /**
     * How many missiles a volley spends (ProjectileFactory::SpendMissiles), [missiles] being the blueprint's
     * `<missiles>` (iSpendMissile, set by ProjectileFactory::Update for every type but BEAM). Two hooks run in this
     * order: CustomWeapons.cpp (priority 0: `random32()%100 < freeMissileChance` makes the volley free; the roll is
     * made for every volley that would spend missiles), then AugmentValues.cpp (priority 1000, a rewrite of the
     * original: Explosive Replicator, `random32()%100 < floor(value*100)`, rolled only with the augment). A free
     * volley raises event 12 for the player (the '0' by the missile counter).
     */
    @JvmStatic
    fun spendMissiles(weapon: AbstractWeaponInstance, missiles: Int): Int {
        var spend = missiles
        if (spend <= 0) return 0
        val ship = weapon.ship
        val chance = weapon.type.hs?.freeMissileChance ?: 0
        if (r32() % 100 < chance) {
            spend = 0
            if (ship.isPlayerShip) ship.replicatorProcPopups++
        }
        if (spend > 0) {
            val aug = ship.getAugmentValue(AugmentBlueprint.EXPLOSIVE_REPLICATOR)
            if (aug != 0f) {
                if (r32() % 100 < kotlin.math.floor(aug * 100.0).toInt()) {
                    if (ship.isPlayerShip) ship.replicatorProcPopups++
                    return 0
                }
            }
        }
        return spend
    }

    /** ProjectileFactory::Update (HS rewrite): beams never set iSpendMissile. */
    @JvmStatic
    fun spendsMissiles(type: AbstractWeaponBlueprint): Boolean = type !is BeamBlueprint

    // ---------------------------------------------------------------- shot limit

    /** ProjectileFactory::HitShotLimit: `shotLimit >= 0 && shotLimit <= shotsFiredAtTarget`. */
    @JvmStatic
    fun hitShotLimit(weapon: AbstractWeaponInstance): Boolean {
        val limit = weapon.type.hs?.shotLimit ?: return false
        return limit >= 0 && limit <= weapon.hsShotsFired
    }

    // ---------------------------------------------------------------- pre-igniter

    /**
     * ProjectileFactory::ForceCoolup with `<preIgniteChargers enabled="true">`: a powered weapon is charged and a
     * charger gets every charge (`chargeLevel = max(1, chargeLevels)`); an unpowered one is left alone.
     * Returns false when the option is off (the engine's vanilla pre-igniter applies).
     */
    @JvmStatic
    fun forceCoolup(weapon: AbstractWeaponInstance): Boolean {
        if (!HS.cfg().preIgniteChargers) return false
        if (weapon.isPowered) {
            weapon.timeCharged = weapon.chargeTime
            weapon.extraCharges = weapon.maxExtraCharges
        }
        return true
    }

    /**
     * WorldManager::CreateShip (CustomShips.cpp, after super) with `<enemyPreigniterFix>`: an enemy with
     * WEAPON_PREIGNITE powers every weapon (WeaponSystem::IncreasePower(requiredPower)) and then pre-ignites them.
     */
    @JvmStatic
    fun enemyCreated(ship: Ship) {
        if (!HS.cfg().enemyPreigniterFix) return
        if (ship.getAugmentValueOrNull(xyz.znix.xftl.augments.AugPreigniter.NAME) == null) return
        val weapons = ship.weapons ?: return
        for (hp in ship.hardpoints) {
            val w = hp.weapon ?: continue
            if (!w.isPowered) weapons.setWeaponPower(w, true)
        }
        for (hp in ship.hardpoints) {
            val w = hp.weapon ?: continue
            if (!forceCoolup(w) && w.isPowered) w.timeCharged = w.type.chargeTime
        }
    }

    // ---------------------------------------------------------------- texts

    private fun text(t: HsText?, tr: xyz.znix.xftl.Translator): String? {
        if (t == null || t.data.isEmpty()) return null
        val s = if (t.isLiteral) t.data else tr[t.data]
        // HS: boost::replace_all(text, "\\n", "\n"), a literal backslash-n becomes a line break
        return s.replace("\\n", "\n")
    }

    /** WeaponBlueprint::GetDescription (not the tooltip variant): the override text, or null for the normal one. */
    @JvmStatic
    fun descriptionOverride(bp: AbstractWeaponBlueprint, tr: xyz.znix.xftl.Translator): String? {
        val def = bp.hs ?: return null
        if (HS.cfg().redesignedWeaponTooltips) text(def.advancedDescriptionOverride, tr)?.let { return it }
        return text(def.descriptionOverride, tr)
    }

    /** WeaponBox::GenerateTooltip: the override text, or null for the normal tooltip. */
    @JvmStatic
    fun tooltipOverride(bp: AbstractWeaponBlueprint, tr: xyz.znix.xftl.Translator): String? {
        val def = bp.hs ?: return null
        if (HS.cfg().redesignedWeaponTooltips) text(def.advancedTooltipOverride, tr)?.let { return it }
        return text(def.tooltipOverride, tr)
    }

    // ---------------------------------------------------------------- icons

    /**
     * WeaponBlueprint::RenderIcon with `<iconReplace>` / `<iconScale>`: both hooks have priority 0 and iconScale
     * is registered after iconReplace, so it is the outer one: the scale (GL_Scale around the icon centre) applies
     * to the replacement too. The replacement is frame 0 of the named animation, centred.
     */
    @JvmStatic
    fun drawIcon(bp: AbstractWeaponBlueprint, def: HsWeaponDef, game: xyz.znix.xftl.game.InGameState,
                 launcher: xyz.znix.xftl.rendering.Image, g: xyz.znix.xftl.rendering.Graphics, cx: Float, cy: Float) {
        val replace = if (def.iconReplace.isNotEmpty()) game.animations.getOrNull(def.iconReplace) else null
        g.pushTransform()
        g.translate(cx, cy)
        if (def.iconScale != 1f) g.scale(def.iconScale, def.iconScale)
        if (replace != null) {
            val img = replace.spriteAt(game, 0)
            img.draw(-(img.width / 2).toFloat(), -(img.height / 2).toFloat())
        } else {
            bp.drawLauncherRotated(launcher, g, -launcher.height / 2f, -launcher.width / 2f)
        }
        g.popTransform()
    }

    /** WeaponBlueprint::GetDimensions with `<iconReplace>`: the replacement's frame size; null = the launcher's. */
    @JvmStatic
    fun iconSize(bp: AbstractWeaponBlueprint, game: xyz.znix.xftl.game.InGameState): IPoint? {
        val name = bp.hs?.iconReplace?.takeIf { it.isNotEmpty() } ?: return null
        val img = game.animations.getOrNull(name)?.spriteAt(game, 0) ?: return null
        return ConstPoint(img.width, img.height)
    }

    // ---------------------------------------------------------------- pinpoint beams

    /** A beam with `<length>` 0 or 1 (HS "pinpoint" beam): one target point, NumTargetsRequired() == 1. */
    @JvmStatic
    fun isPinpoint(type: AbstractWeaponBlueprint): Boolean = type.hs != null && type is BeamBlueprint && type.length <= 1

    /**
     * ProjectileFactory::Fire for a pinpoint beam: the point snaps to the centre of a tile, `(x*35+17, y*35+17.5)`
     * in ship space (our ship space is integer: 17), and the second point is one pixel to the right (angle 0).
     * The player's aim at the enemy ship uses the clicked tile ([click], ship space); everything else (the enemy
     * AI, artillery, drones, a player beam aimed at the own ship) takes a random slot of the room, `random32() %
     * numSlots`.
     */
    @JvmStatic
    fun pinpointAim(weapon: BeamBlueprint.BeamInstance, room: Room, weaponId: Int, click: IPoint?): SelectedTarget.BeamAim {
        val point = if (click != null) {
            ConstPoint(Math.floorDiv(click.x, 35) * 35 + 17, Math.floorDiv(click.y, 35) * 35 + 17)
        } else slotCentre(room, r32() % room.cellCount)
        val aim = SelectedTarget.BeamAim(weapon, weaponId, room.ship, point)
        aim.angle = 0f
        aim.length = (weapon.type as BeamBlueprint).length
        aim.updateHitRooms()
        if (aim.hitRooms.isEmpty()) aim.hitRooms.add(room)
        return aim
    }

    /** ShipGraph::GetSlotWorldPosition + TranslateToGrid: the centre of a room slot, in ship space. */
    @JvmStatic
    fun slotCentre(room: Room, slot: Int): IPoint {
        val cell = room.slotToPoint(slot)
        return ConstPoint(room.offsetX + cell.x * 35 + 17, room.offsetY + cell.y * 35 + 17)
    }
}
