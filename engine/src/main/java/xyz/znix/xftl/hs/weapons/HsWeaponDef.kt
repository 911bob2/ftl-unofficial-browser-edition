// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomWeapons.cpp
// (BlueprintManager::ProcessWeaponBlueprint), src/game/Gameplay/CustomDamage.h, src/game/Ship/ShipSystems/ErosionEffect.cpp
package xyz.znix.xftl.hs.weapons

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig

/**
 * Hyperspace's per-weapon data (CustomWeaponDefinition + its CustomDamageDefinition), read from the weapon
 * blueprint's own children. Built only while [HS.active] (see AbstractWeaponBlueprint.hs); vanilla blueprints
 * carry none.
 *
 * Parsing follows ProcessWeaponBlueprint: children are read in order (a repeated tag: the last one wins, except the
 * list tags, which accumulate), numbers with boost::lexical_cast (a malformed number makes HS's blueprint loading
 * fail; here the item is dropped by HsLoad like other unreadable mod data), booleans with ParseBoolean.
 */
class HsWeaponDef(@JvmField val name: String) {
    @JvmField var shotLimit = -1
    @JvmField var freeMissileChance = 0
    /** TextString(data, isLiteral): an `id` attribute names a text id, else the element's text is shown as is. */
    @JvmField var descriptionOverride: HsText? = null
    @JvmField var advancedDescriptionOverride: HsText? = null
    @JvmField var tooltipOverride: HsText? = null
    @JvmField var advancedTooltipOverride: HsText? = null
    @JvmField var hideEventTooltip = false
    @JvmField var invisibleBeam = false
    @JvmField var simultaneousFire = false
    @JvmField var fireTime = 0f
    @JvmField var angularRadius = -1f
    @JvmField var iconReplace = ""
    @JvmField var iconScale = 1f
    /** `<projectiles><projectile blueprint="X">image</projectile>`: mini projectile image -> weapon blueprint. */
    @JvmField val miniProjectileOverride = LinkedHashMap<String, String>()

    /** CustomDamageDefinition; [HsDamageDef.DEFAULT] when the weapon has none of the damage tags (HS shares one). */
    @JvmField var damage: HsDamageDef = HsDamageDef.DEFAULT

    companion object {
        /** BlueprintManager::ProcessWeaponBlueprint hook body. [type] is the weapon's `<type>` text. */
        @JvmStatic
        fun parse(node: Element): HsWeaponDef {
            val def = HsWeaponDef(node.getAttributeValue("name") ?: "")
            val dmg = HsDamageDef()
            var hasCustomDamage = false
            var hasErosion = false
            val isBeam = node.getChildTextTrim("type") == "BEAM"
            for (child in node.children) {
                val v = child.textTrim
                when (child.name) {
                    "shotLimit" -> def.shotLimit = v.toInt()
                    "freeMissileChance" -> def.freeMissileChance = v.toInt()
                    "descriptionOverride" -> def.descriptionOverride = HsText.of(child)
                    "advancedDescriptionOverride" -> def.advancedDescriptionOverride = HsText.of(child)
                    "tooltipOverride" -> def.tooltipOverride = HsText.of(child)
                    "advancedTooltipOverride" -> def.advancedTooltipOverride = HsText.of(child)
                    "hideEventTooltip" -> def.hideEventTooltip = HsConfig.bool(v)
                    "accuracyMod" -> { hasCustomDamage = true; dmg.accuracyMod = v.toInt() }
                    "droneAccuracyMod" -> { hasCustomDamage = true; dmg.droneAccuracyMod = v.toInt() }
                    "noSysDamage" -> { hasCustomDamage = true; dmg.noSysDamage = HsConfig.bool(v) }
                    "noPersDamage" -> { hasCustomDamage = true; dmg.noPersDamage = HsConfig.bool(v) }
                    // Ion beam fix only valid for beams (ret.type == 2)
                    "ionBeamFix" -> if (isBeam) { hasCustomDamage = true; dmg.ionBeamFix = HsConfig.bool(v) }
                    "invisibleBeam" -> def.invisibleBeam = HsConfig.bool(v)
                    "simultaneousFire" -> def.simultaneousFire = HsConfig.bool(v)
                    "fireTime" -> def.fireTime = v.toFloat()
                    "angularRadius" -> def.angularRadius = v.toFloat()
                    "statBoostChance" -> { hasCustomDamage = true; dmg.statBoostChance = v.toInt() }
                    "statBoosts" -> { hasCustomDamage = true; child.getChildren("statBoost").forEach { dmg.statBoosts += it; dmg.content += HsDamageDef.Node(HsDamageDef.STAT, it) } }
                    "roomStatBoostChance" -> { hasCustomDamage = true; dmg.roomStatBoostChance = v.toInt() }
                    "roomStatBoosts" -> { hasCustomDamage = true; child.getChildren("statBoost").forEach { dmg.roomStatBoosts += it; dmg.content += HsDamageDef.Node(HsDamageDef.ROOM_STAT, it) } }
                    "crewSpawnChance" -> { hasCustomDamage = true; dmg.crewSpawnChance = v.toInt() }
                    // CrewSpawn::ParseCrewSpawn (always parsed: it takes a spawn index); one without a <race> is dropped
                    "spawnCrew" -> {
                        hasCustomDamage = true
                        val keep = !child.getChildTextTrim("race").isNullOrEmpty()
                        if (keep) dmg.crewSpawns += child
                        dmg.content += HsDamageDef.Node(if (keep) HsDamageDef.SPAWN else HsDamageDef.SPAWN_DROPPED, child)
                    }
                    // the effect starts as a copy of ErosionEffect::defaultErosionEffect (hyperspace.xml <defaults><erosion>)
                    "erosion" -> { hasCustomDamage = true; hasErosion = true; dmg.erosion = HsErosion.parse(child, dmg.erosion) }
                    "erosionChance" -> { hasCustomDamage = true; dmg.erosionChance = v.toInt() }
                    // CustomLockdownDefinition::ParseNode; HS also sets the weapon's Damage.bLockdown
                    "customLockdown" -> {
                        hasCustomDamage = true; dmg.customLockdown = child
                        dmg.lockdownDef = xyz.znix.xftl.hs.ship.HsLockdowns.parse(child, dmg.lockdownDef ?: xyz.znix.xftl.hs.ship.HsLockdowns.fresh())
                    }
                    "iconReplace" -> def.iconReplace = child.text   // child->value(), not trimmed
                    "iconScale" -> def.iconScale = v.toFloat()
                    "projectiles" -> for (p in child.getChildren("projectile")) {
                        val bp = p.getAttributeValue("blueprint") ?: continue
                        def.miniProjectileOverride[p.text] = bp
                    }
                }
            }
            // Default chance if tag not specified (10 of 10 if the list is given, 0 otherwise)
            if (dmg.statBoostChance == -1) dmg.statBoostChance = if (dmg.statBoosts.isEmpty()) 0 else 10
            if (dmg.roomStatBoostChance == -1) dmg.roomStatBoostChance = if (dmg.roomStatBoosts.isEmpty()) 0 else 10
            if (dmg.crewSpawnChance == -1) dmg.crewSpawnChance = if (dmg.crewSpawns.isEmpty()) 0 else 10
            if (dmg.erosionChance == -1) dmg.erosionChance = if (hasErosion) 10 else 0
            if (hasCustomDamage) {
                dmg.owner = def.name
                dmg.giveId()
                def.damage = dmg
            }
            return def
        }
    }
}

/** HS's TextString as a weapon tag sets it: an `id` attribute (text library id) or the literal element text. */
class HsText(@JvmField val data: String, @JvmField val isLiteral: Boolean) {
    companion object {
        @JvmStatic
        fun of(e: Element): HsText = e.getAttributeValue("id")?.let { HsText(it, false) } ?: HsText(e.text, true)
    }
}

/**
 * CustomDamageDefinition. Every definition with a custom tag gets an index ([idx]) in [defs], which HS writes into
 * saved projectiles (ProjectileFactory::SaveProjectile); index 0 is the shared default.
 */
class HsDamageDef {
    @JvmField var idx = 0
    /** The weapon blueprint whose tags made this definition; null for the default and for one a script made. */
    @JvmField var owner: String? = null
    @JvmField var accuracyMod = 0
    @JvmField var droneAccuracyMod = 0
    @JvmField var noSysDamage = false
    @JvmField var noPersDamage = false
    @JvmField var ionBeamFix = false
    @JvmField var statBoostChance = -1
    @JvmField var roomStatBoostChance = -1
    /** `<statBoost>` elements; the stat boost workstream parses them (ParseStatBoostNode, source AUGMENT). */
    @JvmField val statBoosts = ArrayList<Element>()
    @JvmField val roomStatBoosts = ArrayList<Element>()
    @JvmField var erosionChance = -1
    @JvmField var erosion: HsErosion = HsErosion.defaults()
    @JvmField var customLockdown: Element? = null
    /** The parsed `<customLockdown>` (a copy of the default lockdown, then the node); null without one. */
    @JvmField var lockdownDef: xyz.znix.xftl.hs.crew.LockdownDefinition? = null
    @JvmField var crewSpawnChance = -1
    /** `<spawnCrew>` elements (CrewSpawn::ParseCrewSpawn, isCrew = false) with a race. */
    @JvmField val crewSpawns = ArrayList<Element>()

    /** The stat boost and crew spawn nodes in document order (HS parses them in this order, giving their ids). */
    class Node(val kind: Int, val element: Element)
    @JvmField val content = ArrayList<Node>()

    /** Parsed by [HsWeapons.loadContent] in the original's blueprint order, before hyperspace.xml's crew content. */
    @JvmField var statBoostDefs: List<xyz.znix.xftl.hs.stat.StatBoostDefinition> = emptyList()
    @JvmField var roomStatBoostDefs: List<xyz.znix.xftl.hs.stat.StatBoostDefinition> = emptyList()
    @JvmField var crewSpawnDefs: List<xyz.znix.xftl.hs.crew.CrewSpawn> = emptyList()

    fun giveId() {
        idx = defs.size
        defs.add(this)
    }

    companion object {
        const val STAT = 0
        const val ROOM_STAT = 1
        const val SPAWN = 2
        const val SPAWN_DROPPED = 3

        @JvmField val DEFAULT = HsDamageDef()

        /** CustomDamageDefinition::customDamageDefs, reset with every HS session (blueprints load once per start). */
        @JvmField val defs = ArrayList<HsDamageDef>().also { it.add(DEFAULT) }

        @JvmStatic
        fun reset() {
            defs.clear(); defs.add(DEFAULT)
        }

        /**
         * A saved projectile's definition: the weapon's that made it when the save names one ([owner], the last parse of
         * that name, as the blueprint that wins), else [defs] at the saved index as HS reads it
         * (`customDamageDefs.at`). The index alone follows the order the blueprints were parsed in, which another mod
         * order or an engine with another parse order changes.
         */
        @JvmStatic
        fun saved(owner: String?, idx: Int?): HsDamageDef? {
            if (owner != null) defs.lastOrNull { it.owner == owner }?.let { return it }
            return idx?.let { defs.getOrNull(it) }
        }
    }
}

/** ErosionEffect: defaults from CustomDamage.h, overridden by the `<erosion>` children. */
class HsErosion {
    @JvmField var speed = 0.63f
    @JvmField var speedMax = 3.15f
    @JvmField var systemRepairMultiplier = 0.75f
    @JvmField var time = 10f
    @JvmField var timeMax = 25f
    @JvmField var erodeShields = false
    @JvmField var roomAnim: Element? = null

    companion object {
        /** ErosionEffect::defaultErosionEffect: CustomDamage.h's values, then hyperspace.xml `<defaults><erosion>`. */
        @JvmStatic
        fun defaults(): HsErosion {
            var e = HsErosion()
            if (HS.active) for (d in HS.cfg().nodesOf("defaults")) for (c in d.getChildren("erosion")) e = parse(c, e)
            return e
        }

        /** ErosionEffect::ParseErosionEffect onto a copy of [base]. */
        @JvmStatic
        fun parse(node: Element, base: HsErosion = HsErosion()): HsErosion {
            val e = HsErosion()
            e.speed = base.speed; e.speedMax = base.speedMax; e.systemRepairMultiplier = base.systemRepairMultiplier
            e.time = base.time; e.timeMax = base.timeMax; e.erodeShields = base.erodeShields; e.roomAnim = base.roomAnim
            for (child in node.children) {
                val v = child.textTrim
                when (child.name) {
                    "speed" -> e.speed = v.toFloat()
                    "maxSpeed" -> e.speedMax = v.toFloat()
                    "time" -> e.time = v.toFloat()
                    "maxTime" -> e.timeMax = v.toFloat()
                    "systemRepairMultiplier" -> e.systemRepairMultiplier = v.toFloat()
                    "erodeShields" -> e.erodeShields = HsConfig.bool(v)
                    "roomAnim" -> e.roomAnim = child
                }
            }
            return e
        }
    }
}
