// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (ParseCrewNode, ParseDeathEffect, ParseAbilityEffect,
// ParseAbilityResource, ParsePowerRequirementsNode, ParseExtraConditionsNode), CrewSpawn.cpp, CustomLockdowns.cpp
package xyz.znix.xftl.hs.crew

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.stat.HsBadCast
import xyz.znix.xftl.hs.stat.HsParse
import xyz.znix.xftl.hs.stat.StatBoostDefinition
import xyz.znix.xftl.hs.stat.StatBoostDefs

/**
 * HS `CustomCrewManager`: the `<crew>` race definitions of hyperspace.xml and the power/resource registries they
 * create. Filled once per HS session by [HsCrewContent.load]; empty (and never touched) in vanilla play.
 */
object CustomCrewManager {
    private val blueprintNames = HashMap<String, CrewDefinition>()
    /** Races in parse order (for reports/diagnostics; HS keeps an unordered_map). */
    @JvmField val races = ArrayList<String>()

    // ActivatedPowerDefinition statics
    @JvmField val powerDefs = ArrayList<ActivatedPowerDefinition>()
    private val nameDefList = HashMap<String, ActivatedPowerDefinition>()
    private val undefinedNameDefList = HashMap<String, ActivatedPowerDefinition>()
    private val activateGroupNameIndexList = HashMap<String, Int>()
    private val replaceGroupNameIndexList = HashMap<String, Int>()
    private var nextActivateGroupNameIndex = 1
    private var nextReplaceGroupNameIndex = 1

    // PowerResourceDefinition statics
    @JvmField val resourceDefs = ArrayList<PowerResourceDefinition>()
    private val resourceNameDefList = HashMap<String, PowerResourceDefinition>()
    private val resourceUndefinedNameDefList = HashMap<String, PowerResourceDefinition>()

    @JvmField val crewSpawnDefs = ArrayList<CrewSpawn>()

    fun reset() {
        blueprintNames.clear(); races.clear()
        powerDefs.clear(); nameDefList.clear(); undefinedNameDefList.clear()
        activateGroupNameIndexList.clear(); replaceGroupNameIndexList.clear()
        nextActivateGroupNameIndex = 1; nextReplaceGroupNameIndex = 1
        resourceDefs.clear(); resourceNameDefList.clear(); resourceUndefinedNameDefList.clear()
        crewSpawnDefs.clear()
        // CustomCrewManager(): a default ActivatedPowerDefinition takes index 0.
        powerDefs.add(ActivatedPowerDefinition())
    }

    init {
        reset()
    }

    /** CustomCrewManager::IsRace. */
    @JvmStatic
    fun isRace(race: String?): Boolean = race != null && blueprintNames.containsKey(race)

    /**
     * CustomCrewManager::GetDefinition: HS returns `&blueprintNames[name]`, so asking for an unknown race creates a
     * default definition (and [isRace] is true for it afterwards). Only HS code paths that do so call this.
     */
    @JvmStatic
    fun definition(name: String): CrewDefinition = blueprintNames.getOrPut(name) {
        CrewDefinition("").also { it.implicit = true }
    }

    /** The definition without HS's insert-on-lookup (for UI/diagnostics). */
    @JvmStatic
    fun definitionOrNull(name: String?): CrewDefinition? = name?.let { blueprintNames[it] }

    private fun parseError(what: String) {
        HS.unsupported("parse", HS.HYPERSPACE_XML, "Error parsing <crew> in hyperspace.xml: $what")
    }

    /** CustomCrewManager::ParseCrewNode. */
    fun parseCrewNode(node: Element) {
        try {
            for (child in node.children) {
                val raceName = child.getAttributeValue("name") ?: continue
                val crew = CrewDefinition(raceName)
                crew.repairSounds.add("repair")

                child.getAttributeValue("drone")?.let { droneType ->
                    crew.canBurn = false; crew.canRepair = false; crew.canSabotage = false; crew.canSuffocate = false
                    crew.controllable = false; crew.canFight = false; crew.canMan = false; crew.canTeleport = false
                    crew.moveSpeedMultiplier = 0.5f
                    when (droneType) {
                        "BOARDER" -> { crew.canFight = true; crew.canSabotage = true; crew.damageMultiplier = 1.2f; crew.maxHealth = 150 }
                        "BATTLE" -> { crew.canFight = true; crew.damageMultiplier = 1.2f; crew.maxHealth = 150 }
                        "BOARDER_ION" -> { crew.canSabotage = true; crew.maxHealth = 125 }
                        "REPAIR" -> { crew.canRepair = true; crew.maxHealth = 25; crew.repairSpeed = 2f; crew.animBase = "repair" }
                    }
                }

                try {
                    for (stat in child.children) parseRaceStat(crew, stat)
                } catch (e: HsBadCast) {
                    parseError(e.message ?: "bad lexical cast")
                }
                blueprintNames[raceName] = crew
                if (raceName !in races) races.add(raceName)
            }
        } catch (e: Throwable) {
            if (e is HsBadCast) throw e
            parseError(e.message ?: e.toString())
        }
    }

    private fun parseRaceStat(crew: CrewDefinition, stat: Element) {
        val v = HsParse.value(stat)
        when (stat.name) {
            "canFight" -> crew.canFight = HsParse.bool(v)
            "controllable" -> crew.controllable = HsParse.bool(v)
            "selectable" -> crew.selectable = HsParse.bool(v)
            "canRepair" -> crew.canRepair = HsParse.bool(v)
            "canSabotage" -> crew.canSabotage = HsParse.bool(v)
            "canMan" -> crew.canMan = HsParse.bool(v)
            "canTeleport" -> crew.canTeleport = HsParse.bool(v)
            "canSuffocate" -> crew.canSuffocate = HsParse.bool(v)
            "canBurn" -> crew.canBurn = HsParse.bool(v)
            "maxHealth" -> crew.maxHealth = HsParse.int(v)
            "stunMultiplier" -> crew.stunMultiplier = HsParse.float(v)
            "moveSpeedMultiplier" -> crew.moveSpeedMultiplier = HsParse.float(v)
            "repairSpeed" -> crew.repairSpeed = HsParse.float(v)
            "damageMultiplier" -> crew.damageMultiplier = HsParse.float(v)
            "rangedDamageMultiplier" -> crew.rangedDamageMultiplier = HsParse.float(v)
            "doorDamageMultiplier" -> crew.doorDamageMultiplier = HsParse.float(v)
            "fireRepairMultiplier" -> crew.fireRepairMultiplier = HsParse.float(v)
            "suffocationModifier" -> crew.suffocationModifier = HsParse.float(v)
            "providesPower" -> crew.providesPower = HsParse.bool(v)
            "bonusPower" -> crew.bonusPower = HsParse.int(v)
            "isTelepathic" -> crew.isTelepathic = HsParse.bool(v)
            "resistsMindControl" -> crew.resistsMindControl = HsParse.bool(v)
            "isAnaerobic" -> crew.isAnaerobic = HsParse.bool(v)
            "fireDamageMultiplier" -> crew.fireDamageMultiplier = HsParse.float(v)
            "persDamageMultiplier" -> crew.persDamageMultiplier = HsParse.float(v)
            "persHealMultiplier" -> crew.persHealMultiplier = HsParse.float(v)
            "canPhaseThroughDoors" -> crew.canPhaseThroughDoors = HsParse.bool(v)
            "healSpeed" -> crew.healSpeed = HsParse.float(v)
            "powerDrain" -> crew.powerDrain = HsParse.int(v)
            "powerDrainFriendly" -> crew.powerDrainFriendly = HsParse.bool(v)
            "deathSounds" -> {
                var male = true
                var female = true
                stat.getAttributeValue("male")?.let { if (HsParse.bool(it)) female = false else male = false }
                stat.getAttributeValue("female")?.let { if (HsParse.bool(it)) male = false else female = false }
                for (n in stat.children) if (n.name == "deathSound") {
                    if (male) crew.deathSounds.add(HsParse.value(n))
                    if (female) crew.deathSoundsFemale.add(HsParse.value(n))
                }
            }
            "shootingSounds" -> for (n in stat.children) if (n.name == "shootingSound") crew.shootingSounds.add(HsParse.value(n))
            "repairSounds" -> {
                crew.repairSounds.clear()
                for (n in stat.children) if (n.name == "repairSound") crew.repairSounds.add(HsParse.value(n))
                stat.getAttributeValue("animSoundFrame")?.let { crew.repairSoundFrame = HsParse.int(it) }
            }
            "passiveStatBoosts" -> for (n in stat.children)
                crew.passiveStatBoosts.add(StatBoostDefs.parse(n, StatBoostDefinition.BoostSource.CREW, false))
            "animBase" -> crew.animBase = v
            "animSheet" -> { crew.animSheet[0] = v; crew.animSheet[1] = v }
            "animSheetFemale" -> crew.animSheet[0] = v
            "shootTimer" -> parseTimer(stat, v, crew.shootTimer)
            "punchTimer" -> parseTimer(stat, v, crew.punchTimer)
            "canPunch" -> crew.canPunch.set(HsParse.bool(v))
            "oxygenChangeSpeed" -> crew.oxygenChangeSpeed = HsParse.float(v)
            "damageTakenMultiplier" -> crew.damageTakenMultiplier = HsParse.float(v)
            "passiveHealAmount" -> crew.passiveHealAmount = HsParse.float(v)
            "truePassiveHealAmount" -> crew.truePassiveHealAmount = HsParse.float(v)
            "healAmount" -> crew.healAmount = HsParse.float(v)
            "trueHealAmount" -> crew.trueHealAmount = HsParse.float(v)
            "passiveHealDelay" -> crew.passiveHealDelay = HsParse.int(v)
            "detectsLifeforms" -> crew.detectsLifeforms = HsParse.bool(v)
            "healCrewAmount" -> crew.healCrewAmount = HsParse.float(v)
            "skills" -> for (n in stat.children) {
                val skill = crew.skillsDef.skills[n.name] ?: continue
                n.getAttributeValue("req")?.let { skill.requirement = HsParse.int(it) }
            }
            "hasCustomDeathAnimation" -> crew.hasCustomDeathAnimation = HsParse.bool(v)
            "sabotageSpeedMultiplier" -> crew.sabotageSpeedMultiplier = HsParse.float(v)
            "defaultSkillLevel" -> crew.defaultSkillLevel = HsParse.int(v)
            "cloneLoseSkills" -> crew.cloneLoseSkills = HsParse.bool(v)
            "allDamageTakenMultiplier" -> crew.allDamageTakenMultiplier = HsParse.float(v)
            "damageEnemiesAmount" -> crew.damageEnemiesAmount = HsParse.float(v)
            "hackDoors" -> crew.hackDoors = HsParse.bool(v)
            "powerRechargeMultiplier" -> crew.powerRechargeMultiplier = HsParse.float(v)
            "nameRace" -> {
                crew.nameRace.add(v)
                stat.getAttributeValue("transformName")?.let { crew.transformName.add(it) }
                stat.getAttributeValue("changeIfSame")?.let { crew.changeIfSame = HsParse.bool(it) }
            }
            "droneAI" -> {
                crew.droneAI.hasCustomAI = true
                for (n in stat.children) when (n.name) {
                    "fightAI" -> crew.droneAI.fightAI = true
                    "repairAI" -> crew.droneAI.repairAI = true
                    "manAI" -> crew.droneAI.manAI = true
                    "batteryAI" -> crew.droneAI.batteryAI = true
                    "returnToDroneRoom" -> crew.droneAI.returnToDroneRoom = true
                }
            }
            "droneMoveFromManningSlot" -> crew.droneMoveFromManningSlot = HsParse.bool(v)
            "deathEffect" -> { crew.hasDeathExplosion = true; parseDeathEffect(stat, crew.explosionDef) }
            "powerEffect" -> crew.powerDefs.add(parseAbilityEffect(stat))
            "powerResource" -> parseAbilityResource(stat) // only defines it
            "crewSlots" -> crew.crewSlots = HsParse.float(v)
            "noSlot" -> crew.noSlot = HsParse.bool(v)
            "noClone" -> crew.noClone = HsParse.bool(v)
            "cloneSpeedMultiplier" -> crew.cloneSpeedMultiplier = HsParse.float(v)
            "noAI" -> crew.noAI = HsParse.bool(v)
            "validTarget" -> crew.validTarget = HsParse.bool(v)
            "canMove" -> crew.canMove = HsParse.bool(v)
            "snapToSlot" -> crew.snapToSlot = HsParse.bool(v)
            "teleportMove" -> crew.teleportMove = HsParse.bool(v)
            "teleportMoveOtherShip" -> crew.teleportMoveOtherShip = HsParse.bool(v)
            "essential" -> crew.essential = HsParse.float(v)
            "silenced" -> crew.silenced = HsParse.bool(v)
            "lowHealthThreshold" -> crew.lowHealthThreshold = HsParse.float(v)
            "lowHealthThresholdPercentage" -> crew.lowHealthThresholdPercentage = HsParse.float(v)
            "noWarning" -> crew.noWarning = HsParse.bool(v)
        }
    }

    private fun parseTimer(stat: Element, v: String, timer: IntArray) {
        if (v.isNotEmpty()) { timer[0] = HsParse.int(v); timer[1] = HsParse.int(v) }
        stat.getAttributeValue("min")?.let { timer[0] = HsParse.int(it); if (timer[1] == -1) timer[1] = HsParse.int(it) }
        stat.getAttributeValue("max")?.let { timer[1] = HsParse.int(it); if (timer[1] == -1) timer[0] = HsParse.int(it) }
    }

    /** CustomLockdownDefinition::ParseNode. */
    private fun parseLockdown(node: Element, def: LockdownDefinition) {
        for (c in node.children) when (c.name) {
            "duration" -> def.duration = HsParse.float(HsParse.value(c))
            "health" -> def.health = HsParse.int(HsParse.value(c))
            "color" -> parseColour(c, def.colour, true)
            "anims" -> def.anims = c.children.map { HsParse.value(it) }
            "canDilate" -> def.canDilate = HsParse.bool(HsParse.value(c))
        }
    }

    /** ParseColorNode (Resources.cpp). */
    @JvmStatic
    fun parseColour(node: Element, c: HsColour, divide: Boolean) {
        val d = if (divide) 255f else 1f
        node.getAttributeValue("r")?.let { c.r = HsParse.float(it) / d }
        node.getAttributeValue("g")?.let { c.g = HsParse.float(it) / d }
        node.getAttributeValue("b")?.let { c.b = HsParse.float(it) / d }
        node.getAttributeValue("a")?.let { c.a = HsParse.float(it) }
    }

    /** CustomCrewManager::ParseDeathEffect: reads into a copy of [into] and writes it back. */
    fun parseDeathEffect(stat: Element, into: ExplosionDefinition) {
        val def = into.copy()
        for (n in stat.children) {
            val v = HsParse.value(n)
            when (n.name) {
                "damage" -> def.damage.iDamage = HsParse.int(v)
                "fireChance" -> def.damage.fireChance = HsParse.int(v)
                "breachChance" -> def.damage.breachChance = HsParse.int(v)
                "ion" -> def.damage.iIonDamage = HsParse.int(v)
                "sysDamage" -> def.damage.iSystemDamage = HsParse.int(v)
                "persDamage" -> def.damage.iPersDamage = HsParse.int(v)
                "hullBust" -> def.damage.bHullBuster = HsParse.bool(v)
                "lockdown" -> def.damage.bLockdown = HsParse.bool(v)
                "customLockdown" -> { parseLockdown(n, def.customLockdown); def.damage.bLockdown = true }
                "friendlyFire" -> def.damage.bFriendlyFire = HsParse.bool(v)
                "stun" -> def.damage.iStun = HsParse.int(v)
                "shipFriendlyFire" -> def.shipFriendlyFire = HsParse.bool(v)
                "transformRace" -> {
                    def.transformRace = v
                    def.transformRaceHealth = 0f
                    def.transformRaceHealthFraction = 1f
                    def.transformRaceDeathSound = false
                    n.getAttributeValue("health")?.let { def.transformRaceHealth = HsParse.float(it); def.transformRaceHealthFraction = 0f }
                    n.getAttributeValue("healthFraction")?.let { def.transformRaceHealthFraction = HsParse.float(it) }
                    n.getAttributeValue("deathSound")?.let { def.transformRaceDeathSound = HsParse.bool(it) }
                }
                "spawnCrew" -> parseCrewSpawn(n, true).let { if (it.race.isNotEmpty()) def.crewSpawns.add(it) }
                "statBoosts" -> for (s in n.children) if (s.name == "statBoost")
                    def.statBoosts.add(StatBoostDefs.parse(s, StatBoostDefinition.BoostSource.CREW, false))
                "roomStatBoosts" -> for (s in n.children) if (s.name == "statBoost")
                    def.roomStatBoosts.add(StatBoostDefs.parse(s, StatBoostDefinition.BoostSource.CREW, true))
                "event" -> { def.eventNodes[0] = n; def.eventNodes[1] = n; def.event[0] = inlineEvent(n); def.event[1] = def.event[0] }
                "playerEvent" -> { def.eventNodes[0] = n; def.event[0] = inlineEvent(n) }
                "enemyEvent" -> { def.eventNodes[1] = n; def.event[1] = inlineEvent(n) }
            }
        }
        into.damage = def.damage; into.shipFriendlyFire = def.shipFriendlyFire; into.statBoosts = def.statBoosts
        into.roomStatBoosts = def.roomStatBoosts; into.crewSpawns = def.crewSpawns; into.transformRace = def.transformRace
        into.transformRaceHealth = def.transformRaceHealth; into.transformRaceHealthFraction = def.transformRaceHealthFraction
        into.transformRaceDeathSound = def.transformRaceDeathSound; into.event[0] = def.event[0]; into.event[1] = def.event[1]
        into.eventNodes = def.eventNodes; into.customLockdown = def.customLockdown
    }

    /**
     * EventsParser::ProcessEvent(node, "__crewDeath"/"__crewAbility"): an event given by name (`<event load=...>` or
     * `name=`) is returned as that name; inline events need the events workstream ([inlineEventHook]).
     */
    private fun inlineEvent(n: Element): String {
        inlineEventHook?.let { return it(n) }
        n.getAttributeValue("load")?.let { return it }
        n.getAttributeValue("name")?.let { return it }
        HS.unsupported("xml", HS.HYPERSPACE_XML, "crew event (inline)")
        return ""
    }

    /** Set by the events workstream: registers an inline event node and returns its name (ProcessEvent). */
    @JvmField var inlineEventHook: ((Element) -> String)? = null

    /** CrewSpawn::ParseCrewSpawn. */
    fun parseCrewSpawn(node: Element, isCrew: Boolean): CrewSpawn {
        val s = CrewSpawn()
        s.idx = crewSpawnDefs.size
        crewSpawnDefs.add(s)
        for (n in node.children) {
            val v = HsParse.value(n)
            when (n.name) {
                "race" -> s.race = v
                "name" -> s.name = v
                "number" -> s.number = HsParse.int(v)
                "healthPercentage" -> s.healthPercentage = HsParse.float(v)
                "lifetime" -> s.lifetime = HsParse.float(v)
                "statBoosts" -> for (b in n.children) s.statBoosts.add(StatBoostDefs.parse(b,
                    if (isCrew) StatBoostDefinition.BoostSource.CREW else StatBoostDefinition.BoostSource.AUGMENT, false))
                "noSlot" -> s.noSlot = HsParse.bool(v)
                "enemy" -> s.enemy = HsParse.bool(v)
            }
        }
        return s
    }

    @JvmStatic
    fun assignIndex(def: ActivatedPowerDefinition) {
        def.index = powerDefs.size
        powerDefs.add(def)
    }

    @JvmStatic
    fun powerByName(name: String): ActivatedPowerDefinition? = nameDefList[name]

    /** ActivatedPowerDefinition::AssignName (Lua). */
    @JvmStatic
    fun assignName(def: ActivatedPowerDefinition, name: String) { def.name = name; nameDefList[name] = def }

    /** PowerResourceDefinition::AssignIndex / AssignName / AssignGroup / AddNamedDefinition (Lua). */
    @JvmStatic
    fun assignResourceIndex(def: PowerResourceDefinition) { def.index = resourceDefs.size; resourceDefs.add(def) }

    @JvmStatic
    fun assignResourceName(def: PowerResourceDefinition, name: String) { def.name = name; resourceNameDefList[name] = def }

    @JvmStatic
    fun addNamedResource(name: String, copyDef: PowerResourceDefinition?): PowerResourceDefinition {
        val undefined = resourceUndefinedNameDefList.remove(name)
        val def = if (undefined == null) {
            PowerResourceDefinition().also { if (copyDef != null) it.assignFrom(copyDef) }
        } else {
            if (copyDef != null) undefined.assignFrom(copyDef)
            undefined
        }
        def.name = name
        resourceNameDefList[name] = def
        return def
    }

    @JvmStatic
    fun addNamedDefinition(name: String, copyDef: ActivatedPowerDefinition?): ActivatedPowerDefinition {
        val undefined = undefinedNameDefList.remove(name)
        val def = if (undefined == null) {
            ActivatedPowerDefinition().also { if (copyDef != null) it.assignFrom(copyDef) }
        } else {
            if (copyDef != null) undefined.assignFrom(copyDef)
            undefined
        }
        def.name = name
        nameDefList[name] = def
        return def
    }

    @JvmStatic
    fun activateGroup(name: String): Int = activateGroupNameIndexList.getOrPut(name) { nextActivateGroupNameIndex++ }

    /** ActivatedPowerDefinition::GetReplaceGroup (shared with power resources' groups). */
    @JvmStatic
    fun replaceGroup(name: String): Int = replaceGroupNameIndexList.getOrPut(name) { nextReplaceGroupNameIndex++ }

    private fun text(n: Element): HsText = n.getAttributeValue("id")?.let { HsText(it, false) } ?: HsText(HsParse.value(n), true)

    private fun cooldownMode(v: String, current: Int): Int = when (v) {
        "full" -> PowerConst.DISABLED_COOLDOWN_FULL
        "reset" -> PowerConst.DISABLED_COOLDOWN_RESET
        "continue" -> PowerConst.DISABLED_COOLDOWN_CONTINUE
        "pause" -> PowerConst.DISABLED_COOLDOWN_PAUSE
        "zero" -> PowerConst.DISABLED_COOLDOWN_ZERO
        else -> current
    }

    private fun jumpMode(v: String, current: Int): Int = when (v) {
        "full" -> PowerConst.JUMP_COOLDOWN_FULL
        "reset" -> PowerConst.JUMP_COOLDOWN_RESET
        "continue" -> PowerConst.JUMP_COOLDOWN_CONTINUE
        else -> current
    }

    private fun deathMode(v: String, current: Int): Int = when (v) {
        "continue" -> PowerConst.ON_DEATH_CONTINUE
        "cancel" -> PowerConst.ON_DEATH_CANCEL
        "reset" -> PowerConst.ON_DEATH_RESET
        else -> current
    }

    /** CustomCrewManager::ParseAbilityEffect. A `load` of a power not yet defined yields null, as in HS. */
    fun parseAbilityEffect(stat: Element): ActivatedPowerDefinition? {
        var name = ""
        stat.getAttributeValue("load")?.let { load ->
            val def = powerByName(load)
            if (def == null && !undefinedNameDefList.containsKey(load))
                undefinedNameDefList[load] = ActivatedPowerDefinition().also { it.name = load } // allows the power to be defined later
            return def
        }
        var copyDef: ActivatedPowerDefinition? = null
        stat.getAttributeValue("copy")?.let { name = it; copyDef = powerByName(it) }
        val def: ActivatedPowerDefinition = when {
            stat.getAttributeValue("name") != null -> { name = stat.getAttributeValue("name"); addNamedDefinition(name, copyDef) }
            copyDef != null -> ActivatedPowerDefinition().also { it.assignFrom(copyDef!!) }
            else -> ActivatedPowerDefinition()
        }
        assignIndex(def)
        def.hasSpecialPower = true
        if (name.isNotEmpty()) def.name = name

        for (e in stat.children) {
            val v = HsParse.value(e)
            when (e.name) {
                "groupName" -> { def.activateGroupIndex = activateGroup(v); def.replaceGroupIndex = replaceGroup(v) }
                "activateGroupName" -> def.activateGroupIndex = activateGroup(v)
                "replaceGroupName" -> def.replaceGroupIndex = replaceGroup(v)
                "powerSounds" -> {
                    e.getAttributeValue("enemy")?.let { def.soundsEnemy = HsParse.bool(it) }
                    for (s in e.children) if (s.name == "powerSound") def.sounds.add(HsParse.value(s))
                }
                "powerEffectSounds" -> {
                    e.getAttributeValue("enemy")?.let { def.effectSoundsEnemy = HsParse.bool(it) }
                    for (s in e.children) if (s.name == "powerSound") def.effectSounds.add(HsParse.value(s))
                }
                "req" -> {
                    val req = ActivatedPowerRequirements()
                    parsePowerRequirements(e, req)
                    when (e.getAttributeValue("type")) {
                        "player" -> def.playerReq = req.also { it.type = ActivatedPowerRequirements.Type.PLAYER }
                        "enemy" -> def.enemyReq = req.also { it.type = ActivatedPowerRequirements.Type.ENEMY }
                        else -> {
                            def.enemyReq = req.copy().also { it.type = ActivatedPowerRequirements.Type.ENEMY }
                            def.playerReq = req.copy().also { it.type = ActivatedPowerRequirements.Type.PLAYER }
                        }
                    }
                }
                "chargeReq" -> def.chargeReq = ActivatedPowerRequirements(ActivatedPowerRequirements.Type.CHARGE).also { parsePowerRequirements(e, it) }
                "jumpCooldown" -> def.jumpCooldown = jumpMode(v, def.jumpCooldown)
                "disabledCooldown" -> def.disabledCooldown = cooldownMode(v, def.disabledCooldown)
                "disabledCharges" -> def.disabledCharges = cooldownMode(v, def.disabledCharges)
                "initialCooldownFraction" -> def.initialCooldownFraction = HsParse.float(v)
                "onDeath" -> def.onDeath = deathMode(v, def.onDeath)
                "onHotkey" -> def.onHotkey = when (v) {
                    "first" -> PowerConst.HOTKEY_FIRST; "always" -> PowerConst.HOTKEY_ALWAYS; "never" -> PowerConst.HOTKEY_NEVER
                    else -> def.onHotkey
                }
                "damage" -> def.damage.iDamage = HsParse.int(v)
                "fireChance" -> def.damage.fireChance = HsParse.int(v)
                "breachChance" -> def.damage.breachChance = HsParse.int(v)
                "ion" -> def.damage.iIonDamage = HsParse.int(v)
                "sysDamage" -> def.damage.iSystemDamage = HsParse.int(v)
                "persDamage" -> def.damage.iPersDamage = HsParse.int(v)
                "hullBust" -> def.damage.bHullBuster = HsParse.bool(v)
                "lockdown" -> def.damage.bLockdown = HsParse.bool(v)
                "customLockdown" -> { parseLockdown(e, def.customLockdown); def.damage.bLockdown = true }
                "friendlyFire" -> def.damage.bFriendlyFire = HsParse.bool(v)
                "stun" -> def.damage.iStun = HsParse.int(v)
                "cooldown" -> def.cooldown = HsParse.float(v)
                "powerCharges" -> def.powerCharges = HsParse.int(v)
                "initialCharges" -> def.initialCharges = HsParse.int(v)
                "respawnCharges" -> def.respawnCharges = HsParse.int(v)
                "chargesPerJump" -> def.chargesPerJump = HsParse.int(v)
                "shipFriendlyFire" -> def.shipFriendlyFire = HsParse.bool(v)
                "win" -> def.win = HsParse.bool(v)
                "animFrame" -> {
                    def.animFrame = HsParse.int(v)
                    e.getAttributeValue("followCrew")?.let { def.followCrew = HsParse.bool(it) }
                }
                "buttonText" -> def.buttonLabel = text(e)
                "tooltip" -> def.tooltip = text(e)
                "cooldownColor" -> parseColour(e, def.cooldownColour, true)
                "effectAnim" -> def.effectAnim = v
                "effectPostAnim" -> def.effectPostAnim = v
                "crewHealth" -> def.crewHealth = HsParse.float(v)
                "enemyHealth" -> def.enemyHealth = HsParse.float(v)
                "selfHealth" -> def.selfHealth = HsParse.float(v)
                "activateWhenReady" -> {
                    def.activateWhenReady = HsParse.bool(v)
                    e.getAttributeValue("enemiesOnly")?.let { def.activateReadyEnemies = HsParse.bool(it) }
                }
                "transformRace" -> def.transformRace = v
                "spawnCrew" -> parseCrewSpawn(e, true).let { if (it.race.isNotEmpty()) def.crewSpawns.add(it) }
                "statBoosts" -> for (s in e.children) if (s.name == "statBoost")
                    def.statBoosts.add(StatBoostDefs.parse(s, StatBoostDefinition.BoostSource.CREW, false))
                "roomStatBoosts" -> for (s in e.children) if (s.name == "statBoost")
                    def.roomStatBoosts.add(StatBoostDefs.parse(s, StatBoostDefinition.BoostSource.CREW, true))
                "event" -> { def.eventNodes[0] = e; def.eventNodes[1] = e; def.event[0] = inlineEvent(e); def.event[1] = def.event[0] }
                "playerEvent" -> { def.eventNodes[0] = e; def.event[0] = inlineEvent(e) }
                "enemyEvent" -> { def.eventNodes[1] = e; def.event[1] = inlineEvent(e) }
                "sortOrder" -> def.sortOrder = HsParse.int(v)
                "hideCooldown" -> def.hideCooldown = HsParse.bool(v)
                "hideCharges" -> def.hideCharges = HsParse.bool(v)
                "hideButton" -> def.hideButton = HsParse.bool(v)
                "powerResource" -> def.powerResources.add(parseAbilityResource(e))
                "temporaryEffect" -> { def.hasTemporaryPower = true; parseTemporaryEffect(e, def.tempPower) }
            }
        }
        return def
    }

    private fun parseTemporaryEffect(node: Element, t: TemporaryPowerDefinition) {
        for (e in node.children) {
            val v = HsParse.value(e)
            when (e.name) {
                "duration" -> t.duration = HsParse.float(v)
                "cooldownColor" -> parseColour(e, t.cooldownColour, true)
                "finishSounds" -> {
                    e.getAttributeValue("enemy")?.let { t.soundsEnemy = HsParse.bool(it) }
                    for (s in e.children) if (s.name == "finishSound") t.sounds.add(HsParse.value(s))
                }
                "stunMultiplier" -> t.stunMultiplier.set(HsParse.float(v))
                "moveSpeedMultiplier" -> t.moveSpeedMultiplier.set(HsParse.float(v))
                "repairSpeed" -> t.repairSpeed.set(HsParse.float(v))
                "damageMultiplier" -> t.damageMultiplier.set(HsParse.float(v))
                "rangedDamageMultiplier" -> t.rangedDamageMultiplier.set(HsParse.float(v))
                "doorDamageMultiplier" -> t.doorDamageMultiplier.set(HsParse.float(v))
                "bonusPower" -> t.bonusPower.set(HsParse.int(v))
                "animSheet" -> {
                    t.animSheet = v
                    e.getAttributeValue("baseVisible")?.let { t.baseVisible = HsParse.bool(it) }
                }
                "effectAnim" -> t.effectAnim = v
                "invulnerable" -> t.invulnerable = HsParse.bool(v)
                "hackDoors" -> t.hackDoors.set(HsParse.bool(v))
                "controllable" -> t.controllable.set(HsParse.bool(v))
                "canFight" -> t.canFight.set(HsParse.bool(v))
                "canRepair" -> t.canRepair.set(HsParse.bool(v))
                "canSabotage" -> t.canSabotage.set(HsParse.bool(v))
                "canMan" -> t.canMan.set(HsParse.bool(v))
                "canTeleport" -> t.canTeleport.set(HsParse.bool(v))
                "canSuffocate" -> t.canSuffocate.set(HsParse.bool(v))
                "suffocationModifier" -> t.suffocationModifier.set(HsParse.float(v))
                "oxygenChangeSpeed" -> t.oxygenChangeSpeed.set(HsParse.float(v))
                "canPhaseThroughDoors" -> t.canPhaseThroughDoors.set(HsParse.bool(v))
                "detectsLifeforms" -> t.detectsLifeforms.set(HsParse.bool(v))
                "isTelepathic" -> t.isTelepathic.set(HsParse.bool(v))
                "resistsMindControl" -> t.resistsMindControl.set(HsParse.bool(v))
                "fireDamageMultiplier" -> t.fireDamageMultiplier.set(HsParse.float(v))
                "persDamageMultiplier" -> t.persDamageMultiplier.set(HsParse.float(v))
                "persHealMultiplier" -> t.persHealMultiplier.set(HsParse.float(v))
                "damageTakenMultiplier" -> t.damageTakenMultiplier.set(HsParse.float(v))
                "allDamageTakenMultiplier" -> t.allDamageTakenMultiplier.set(HsParse.float(v))
                "sabotageSpeedMultiplier" -> t.sabotageSpeedMultiplier.set(HsParse.float(v))
                "passiveHealAmount" -> t.passiveHealAmount.set(HsParse.float(v))
                "healAmount" -> t.healAmount.set(HsParse.float(v))
                "truePassiveHealAmount" -> t.truePassiveHealAmount.set(HsParse.float(v))
                "trueHealAmount" -> t.trueHealAmount.set(HsParse.float(v))
                "damageEnemiesAmount" -> t.damageEnemiesAmount.set(HsParse.float(v))
                "animFrame" -> t.animFrame = HsParse.int(v)
                "healCrewAmount" -> t.healCrewAmount.set(HsParse.float(v))
                "effectFinishAnim" -> t.effectFinishAnim = v
                "powerDrain" -> t.powerDrain.set(HsParse.int(v))
                "powerDrainFriendly" -> t.powerDrainFriendly.set(HsParse.bool(v))
                "powerRechargeMultiplier" -> t.powerRechargeMultiplier.set(HsParse.float(v))
                "statBoosts" -> for (s in e.children) t.statBoosts.add(StatBoostDefs.parse(s, StatBoostDefinition.BoostSource.CREW, false))
                "noClone" -> t.noClone.set(HsParse.bool(v))
                "cloneSpeedMultiplier" -> t.cloneSpeedMultiplier.set(HsParse.float(v))
                "noAI" -> t.noAI.set(HsParse.bool(v))
                "validTarget" -> t.validTarget.set(HsParse.bool(v))
                "canMove" -> t.canMove.set(HsParse.bool(v))
                "teleportMove" -> t.teleportMove.set(HsParse.bool(v))
                "teleportMoveOtherShip" -> t.teleportMoveOtherShip.set(HsParse.bool(v))
                "silenced" -> t.silenced.set(HsParse.bool(v))
                "lowHealthThreshold" -> t.lowHealthThreshold.set(HsParse.float(v))
                "noWarning" -> t.noWarning.set(HsParse.bool(v))
            }
        }
    }

    @JvmStatic
    fun resourceByName(name: String): PowerResourceDefinition? = resourceNameDefList[name]

    /** CustomCrewManager::ParseAbilityResource. */
    fun parseAbilityResource(stat: Element): PowerResourceDefinition? {
        var name = ""
        stat.getAttributeValue("load")?.let { load ->
            val def = resourceByName(load)
            if (def == null && !resourceUndefinedNameDefList.containsKey(load))
                resourceUndefinedNameDefList[load] = PowerResourceDefinition().also { it.name = load }
            return def
        }
        var copyDef: PowerResourceDefinition? = null
        stat.getAttributeValue("copy")?.let { name = it; copyDef = resourceByName(it) }
        val def: PowerResourceDefinition = when {
            stat.getAttributeValue("name") != null -> {
                name = stat.getAttributeValue("name")
                val undefined = resourceUndefinedNameDefList.remove(name)
                val d = if (undefined == null) PowerResourceDefinition().also { if (copyDef != null) it.assignFrom(copyDef!!) }
                else undefined.also { if (copyDef != null) it.assignFrom(copyDef!!) }
                d.name = name
                resourceNameDefList[name] = d
                d
            }
            copyDef != null -> PowerResourceDefinition().also { it.assignFrom(copyDef!!) }
            else -> PowerResourceDefinition()
        }
        def.index = resourceDefs.size
        resourceDefs.add(def)
        if (name.isNotEmpty()) def.name = name

        for (e in stat.children) {
            val v = HsParse.value(e)
            when (e.name) {
                "groupName" -> def.groupIndex = replaceGroup(v)
                "chargeReq" -> def.chargeReq = ActivatedPowerRequirements(ActivatedPowerRequirements.Type.CHARGE).also { parsePowerRequirements(e, it) }
                "jumpCooldown" -> def.jumpCooldown = jumpMode(v, def.jumpCooldown)
                "disabledCooldown" -> def.disabledCooldown = cooldownMode(v, def.disabledCooldown)
                "disabledCharges" -> def.disabledCharges = cooldownMode(v, def.disabledCharges)
                "initialCooldownFraction" -> def.initialCooldownFraction = HsParse.float(v)
                "onDeath" -> def.onDeath = deathMode(v, def.onDeath)
                "cooldown" -> def.cooldown = HsParse.float(v)
                "powerCharges" -> def.powerCharges = HsParse.int(v)
                "initialCharges" -> def.initialCharges = HsParse.int(v)
                "respawnCharges" -> def.respawnCharges = HsParse.int(v)
                "chargesPerJump" -> def.chargesPerJump = HsParse.int(v)
                "cooldownColor" -> parseColour(e, def.cooldownColour, true)
                "sortOrder" -> def.sortOrder = HsParse.int(v)
                "hideCooldown" -> def.hideCooldown = HsParse.bool(v)
                "hideCharges" -> def.hideCharges = HsParse.bool(v)
                "showTemporaryBars" -> def.showTemporaryBars = HsParse.bool(v)
                "showLinkedCooldowns" -> def.showLinkedCooldowns = HsParse.bool(v)
                "showLinkedCharges" -> def.showLinkedCharges = HsParse.bool(v)
            }
        }
        return def
    }

    private fun appendNames(list: MutableList<String>, node: Element) {
        for (c in node.children) {
            if (c.name == "blueprintList" && c.getAttributeValue("load") != null) list.addAll(StatBoostDefs.blueprintList(c.getAttributeValue("load")))
            else list.add(c.name)
        }
    }

    /** CustomCrewManager::ParsePowerRequirementsNode. */
    fun parsePowerRequirements(node: Element, def: ActivatedPowerRequirements) {
        for (r in node.children) {
            val v = HsParse.value(r)
            when (r.name) {
                "enemyShip" -> def.enemyShip = true
                "playerShip" -> def.playerShip = true
                "friendlyInRoom" -> { def.friendlyInRoom = true; def.checkRoomCrew = true }
                "enemyInRoom" -> { def.enemyInRoom = true; def.checkRoomCrew = true }
                "notMindControlled" -> def.extraConditions.add(CrewExtraCondition.MIND_CONTROLLED to false)
                "whiteList" -> {
                    var list: MutableList<String> = def.whiteList
                    r.getAttributeValue("enemy")?.let { list = if (HsParse.bool(it)) def.enemyWhiteList else def.friendlyWhiteList }
                    // HS: a `load` list goes into a new vector that is never stored (a leak), so its names are lost.
                    if (r.getAttributeValue("load") != null) list = ArrayList(StatBoostDefs.blueprintList(r.getAttributeValue("load")))
                    appendNames(list, r)
                    def.checkRoomCrew = true
                }
                "blackList" -> {
                    var friendlyList = true
                    var enemyList = true
                    r.getAttributeValue("enemy")?.let { enemyList = HsParse.bool(it); friendlyList = !enemyList }
                    r.getAttributeValue("load")?.let { load ->
                        if (friendlyList) def.friendlyBlackList = ArrayList(StatBoostDefs.blueprintList(load))
                        if (enemyList) def.enemyBlackList = ArrayList(StatBoostDefs.blueprintList(load))
                    }
                    if (friendlyList) appendNames(def.friendlyBlackList, r)
                    if (enemyList) appendNames(def.enemyBlackList, r)
                    def.checkRoomCrew = true
                }
                "systemInRoom" -> {
                    def.systemInRoom = true
                    r.getAttributeValue("damaged")?.let { def.systemDamaged = HsParse.bool(it) }
                }
                "hasClonebay" -> def.hasClonebay = true
                "aiDisabled" -> def.aiDisabled = true
                "outOfCombat" -> def.outOfCombat = true
                "inCombat" -> def.inCombat = true
                "isManning" -> def.extraConditions.add(CrewExtraCondition.MANNING to true)
                "requiredSystem" -> {
                    def.requiredSystem = StatBoostDefs.systemId(v)
                    r.getAttributeValue("functional")?.let { def.requiredSystemFunctional = HsParse.bool(it) }
                }
                "minHealth" -> def.minHealth.set(HsParse.int(v))
                "maxHealth" -> def.maxHealth.set(HsParse.int(v))
                "extraConditions" -> {
                    val isOr = r.getAttributeValue("type") == "or"
                    parseExtraConditions(r, if (isOr) def.extraOrConditions else def.extraConditions)
                    if (isOr) {
                        r.getAttributeValue("text")?.let { def.extraOrConditionsTooltip = HsText(it, true) }
                            ?: r.getAttributeValue("textId")?.let { def.extraOrConditionsTooltip = HsText(it, false) }
                        if (def.extraOrConditionsTooltip.data.isEmpty()) def.extraOrConditionsTooltip.data = "power_not_ready_generic"
                    }
                }
            }
        }
    }

    /** CustomCrewManager::ParseExtraConditionsNode. */
    @JvmStatic
    fun parseExtraConditions(node: Element, list: MutableList<Pair<CrewExtraCondition, Boolean>>) {
        for (c in node.children) {
            val cond = CrewExtraCondition.values().firstOrNull { it.xmlName == c.name } ?: continue
            list.add(cond to HsParse.bool(HsParse.value(c)))
        }
    }
}
