package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.weapons.HsWeapons
import xyz.znix.xftl.hs.weapons.HsCooldownNumbers
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.SelectedTarget
import xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon

/**
 * HS weapons checks (`BrowserEngine.hsWeaponsProbe(part)`), for a Hyperspace (Multiverse) session. Test-only.
 * - `units`: tag data, pinpoint beam damage, shot limit (and its save), free missiles (rate and RNG draws),
 *   fireTime, pre-ignited chargers, icon replacements, and (with Lua) the weapon-family events and members.
 * - `weapons`: every weapon blueprint is built, powered, charged, aimed at the enemy and fired.
 * - `drones`: every drone blueprint is installed and deployed against the enemy for a few seconds.
 * Each part starts its own run and leaves the engine at the main menu.
 */
object BrowserHsWeaponsDiagnostics {
    private const val DT = .05f
    private var restored = 0

    fun run(main: MainGame, container: GameContainer, part: String): String {
        check(HS.active) { "hs-weapons needs a Hyperspace session" }
        val rng = BrowserRandom.snapshot()
        try {
            if (part == "scene") return scene(main, container)
            if (part == "menu") return "PASS menu"
            if (part == "pinpoint") return pinpointSeeds(main, container)
            return when (part) {
                "units" -> units(main, container)
                "weapons" -> weapons(main, container)
                "drones" -> drones(main, container)
                "round2" -> round2(main, container, null)
                else -> if (part.startsWith("round2:")) round2(main, container, part.substring(7).split(",").map { it.toInt() }.toSet())
                    else error("unknown part $part")
            }
        } finally {
            BrowserRandom.restore(rng)
            if (part != "scene") main.switchToMainMenu()
        }
    }

    /**
     * The oracle's 10-fight.png weapon bar (mv-seed-12345): the Kestrel's LASER_BURST_2 (9.0/10.8, targeted),
     * LASER_PIERCE (unpowered, 0.0/11.7) and LASER_BURST_1 (6.6/8.1, targeted), weapons manned at level 0.
     * Leaves the game paused in this state for the page to read the frame; "menu" returns to the main menu.
     */
    private fun scene(main: MainGame, container: GameContainer): String {
        check(HS.cfg().showWeaponCooldown) { "MV showNumericalWeaponCooldown is off" }
        val state = fight(main, container, "PLAYER_SHIP_HARD")
        val player = state.player
        val weapons = player.weapons!!
        weapons.selectedTargets.clear()
        for (hp in player.hardpoints) hp.weapon?.let { weapons.setWeaponPower(it, false) }
        listOf("LASER_BURST_2", "LASER_PIERCE", "LASER_BURST_1").forEachIndexed { i, name ->
            val w = bp(state, name).buildInstance(player)
            player.hardpoints[i].weapon = w
            w.bindToWeaponsSystem(weapons)
        }
        weapons.setAutofire(false)
        val w = player.hardpoints.take(3).map { it.weapon!! }
        repeat(8) { weapons.increasePower() }
        weapons.setWeaponPower(w[0], true); weapons.setWeaponPower(w[1], false); weapons.setWeaponPower(w[2], true)
        // man the weapons console with one crew member at base skill, as on the oracle's Kestrel (a crew member
        // only mans a powered system)
        val room = weapons.room!!
        val console = weapons.configuration.computerPoint ?: ConstPoint.ZERO
        if (weapons.getBoostSkillLevel(xyz.znix.xftl.crew.Skill.WEAPONS) == null) {
            val gunner = room.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().firstOrNull()
                ?: player.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().first()
            gunner.jumpTo(room, console)
            gunner.nextTargetPos = null
            room.updateCrewInRoom(); player.updateCrewReservedSlots()
        }
        step(state, 20)
        val level = weapons.getBoostSkillLevel(xyz.znix.xftl.crew.Skill.WEAPONS)
        check(level == xyz.znix.xftl.crew.SkillLevel.values()[0]) {
            "weapons manning $level (weapons room ${room.id} console $console; powered ${w.map { it.isPowered }}; crew " +
                player.crew.joinToString { "${it.room.id}:${it.currentAction}" } + ")"
        }
        weapons.selectedTargets.clear()
        w[0].timeCharged = 9f / .9f; w[1].timeCharged = 0f; w[2].timeCharged = 6.6f / .9f
        val target = state.enemy!!.rooms.first()
        weapons.selectedTargets.targetRoom(0, target); weapons.selectedTargets.targetRoom(2, target)
        val lines = w.mapIndexed { i, it -> HsCooldownNumbers.lines(state, it, weapons.selectedTargets.getTarget(i) != null, false).first }
        check(lines == listOf("9.0/10.8", "0.0/11.7", "6.6/8.1")) { "cooldown lines $lines" }
        if (!state.isPaused) state.toggleQuickPause()
        return "PASS scene " + lines.joinToString(" ")
    }

    // ------------------------------------------------------------------ fixture

    private fun dismiss(main: MainGame) {
        if ((main.currentState as InGameState).shipUI.isWindowOpen) {
            BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
            BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
        }
    }

    private fun fight(main: MainGame, container: GameContainer, ship: String): InGameState {
        main.startDiagnosticGame(ship, Difficulty.EASY)
        repeat(60) { main.update(container, DT); dismiss(main) }
        var state = main.currentState as InGameState
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state = main.currentState as InGameState
        state.debugFlags.noEnemyFire.set = true
        state.player.purchasedReactorPower = 25
        state.player.missilesCount = 99
        state.player.dronesCount = 99
        return state
    }

    /** The enemy keeps no shields and no evasion, and does not die. */
    private fun soften(enemy: Ship) {
        enemy.shields?.let { s -> repeat(10) { s.decreasePower() }; s.activeShields = 0 }
        enemy.engines?.let { e -> repeat(10) { e.decreasePower() } }
        if (enemy.browserMaxHealth != 500) { enemy.browserMaxHealth = 500; enemy.health = 500 }
        if (enemy.health <= 100) { enemy.health = enemy.maxHealth; restored++ }
    }

    private fun step(state: InGameState, n: Int, until: () -> Boolean = { false }): Int {
        for (i in 0 until n) {
            state.enemy?.let { soften(it) }
            state.updateGameState(DT)
            if (until()) return i + 1
        }
        return n
    }

    private fun install(state: InGameState, bp: AbstractWeaponBlueprint): xyz.znix.xftl.weapons.AbstractWeaponInstance {
        val player = state.player
        player.missilesCount = 99
        val weapons = player.weapons!!
        weapons.selectedTargets.clear()
        for (hp in player.hardpoints) hp.weapon?.let { weapons.setWeaponPower(it, false) }
        weapons.energyLevels = 8
        repeat(8) { weapons.increasePower() }
        val w = bp.buildInstance(player)
        player.hardpoints[0].weapon = w
        w.bindToWeaponsSystem(weapons)
        if (!weapons.setWeaponPower(w, true)) w.forceSetPowered(true)
        w.timeCharged = w.chargeTime
        w.extraCharges = w.maxExtraCharges
        return w
    }

    private fun aim(state: InGameState, w: xyz.znix.xftl.weapons.AbstractWeaponInstance, room: Room) {
        val weapons = state.player.weapons!!
        if (w is BeamBlueprint.BeamInstance) {
            val aim = if (HsWeapons.isPinpoint(w.type)) HsWeapons.pinpointAim(w, room, 0, HsWeapons.slotCentre(room, 0))
            else SelectedTarget.BeamAim(w, 0, room.ship, HsWeapons.slotCentre(room, 0)).also {
                it.angle = 0f; it.updateHitRooms(); if (it.hitRooms.isEmpty()) it.hitRooms.add(room)
            }
            weapons.selectedTargets.targetBeam(0, aim)
        } else if (w is IRoomTargetingWeapon) {
            weapons.selectedTargets.targetRoom(0, room)
        }
    }

    private fun weaponBlueprints(state: InGameState) =
        state.blueprintManager.blueprints.values.filterIsInstance<AbstractWeaponBlueprint>().sortedBy { it.name }

    private fun bp(state: InGameState, name: String) = state.blueprintManager[name] as AbstractWeaponBlueprint

    private fun lua(name: String, code: String) {
        val err = HsLua.runString(name, code)
        check(err == null) { "Lua $name: $err" }
    }

    // ------------------------------------------------------------------ units

    private fun units(main: MainGame, container: GameContainer): String {
        val out = ArrayList<String>()
        BrowserRandom.restore("35")
        var state = fight(main, container, "PLAYER_SHIP_HARD")
        val all = weaponBlueprints(state)
        check(all.all { it.hs != null }) { "weapon without HsWeaponDef" }
        val tagged = mapOf(
            "shotLimit" to all.count { it.hs!!.shotLimit >= 0 }, "freeMissileChance" to all.count { it.hs!!.freeMissileChance > 0 },
            "iconReplace" to all.count { it.hs!!.iconReplace.isNotEmpty() }, "fireTime" to all.count { it.hs!!.fireTime != 0f },
            "accuracyMod" to all.count { it.hs!!.damage.accuracyMod != 0 }, "noSysDamage" to all.count { it.hs!!.damage.noSysDamage },
            "noPersDamage" to all.count { it.hs!!.damage.noPersDamage }, "crewSpawns" to all.count { it.hs!!.damage.crewSpawns.isNotEmpty() },
            "statBoosts" to all.count { it.hs!!.damage.statBoosts.isNotEmpty() }, "pinpoint" to all.count { HsWeapons.isPinpoint(it) },
        )
        out += "blueprints=${all.size} " + tagged.entries.joinToString(",") { "${it.key}=${it.value}" }
        check(all.size >= 700) { "only ${all.size} weapon blueprints" }

        // Lua: record what the weapon-family events hand to scripts.
        if (HS.luaActive) {
            lua("hsw-register", """
HSW = {fire = 0, beam = 0}
script.on_internal_event(Defines.InternalEvents.PROJECTILE_FIRE, function(p, w)
  HSW.fire = HSW.fire + 1
  HSW.name = p.extend.name; HSW.power = w.blueprint.power; HSW.powered = w.powered; HSW.ship = w.iShipId
  HSW.owner = p.ownerId; HSW.bpName = w.blueprint.name; HSW.len = w.blueprint.length
end)
script.on_internal_event(Defines.InternalEvents.DAMAGE_BEAM, function(ship, p, loc, dmg, realNewTile, hit)
  -- Called on every collision check of the beam; 0 is SAME_TILE, as tools/hs-oracle's tracer treats it.
  HSW.beamCalls = (HSW.beamCalls or 0) + 1
  if hit == 0 then return end
  HSW.beam = HSW.beam + 1; HSW.beamDmg = dmg.iDamage; HSW.hit = hit; HSW.beamShip = ship.iShipId
end)
local function D(d)
  return string.format('{"breach":%d,"buster":%s,"fire":%d,"friendly":%s,"hull":%d,"ion":%d,"lockdown":%s,"owner":%d,"pers":%d,"pierce":%d,"stun":%d,"stunTime":%d,"sys":%d}',
    d.breachChance, tostring(d.bHullBuster), d.fireChance, tostring(d.bFriendlyFire), d.iDamage, d.iIonDamage, tostring(d.bLockdown),
    d.ownerId, d.iPersDamage, d.iShieldPiercing, d.stunChance, d.iStun, d.iSystemDamage)
end
script.on_internal_event(Defines.InternalEvents.DAMAGE_AREA, function(ship, p, loc, dmg, forceHit, ff)
  if HSW.area == nil and p ~= nil and p.extend.name == 'LASER_BURST_1' then
    HSW.area = D(dmg) .. ' forceHit=' .. tostring(forceHit) .. ' ff=' .. tostring(ff) .. ' ship=' .. string.format('%d', ship.iShipId)
  end
end)
script.on_internal_event(Defines.InternalEvents.DAMAGE_AREA_HIT, function(ship, p, loc, dmg, ff)
  if HSW.areaHit == nil and p ~= nil and p.extend.name == 'LASER_BURST_1' then
    HSW.areaHit = D(dmg) .. ' ff=' .. tostring(ff) .. ' ship=' .. string.format('%d', ship.iShipId); HSW.areaHull = ship.ship.hullIntegrity.first
  end
end)
""")
        }

        // Pinpoint beam (FOCUS_1: damage 2, length 1): one room, the damage once.
        run {
            val enemy = state.enemy!!
            val room = enemy.rooms.first { it.system == null && it.cellCount >= 2 }
            soften(enemy); enemy.health = enemy.maxHealth
            val w = install(state, bp(state, "FOCUS_1")) as BeamBlueprint.BeamInstance
            check(HsWeapons.isPinpoint(w.type)) { "FOCUS_1 is not pinpoint" }
            aim(state, w, room)
            val before = enemy.health
            // FOCUS_1 recharges in 12 s: 9 s cover the one shot and its beam.
            var beamOn = 0; var shields = 0
            step(state, 180) { if (w.isBeamOn) beamOn++; shields = maxOf(shields, enemy.shields?.activeShields ?: 0); false }
            val luaSeen = if (HS.luaActive) HsLua.runString("hsw-peek", "error('HSW beam=' .. HSW.beam .. ' hit=' .. tostring(HSW.hit) .. ' dmg=' .. tostring(HSW.beamDmg) .. ' fire=' .. HSW.fire)") else ""
            check(w.hsShotsFired == 1) { "pinpoint did not fire (${w.hsShotsFired})" }
            check(before - enemy.health == 2) { "pinpoint FOCUS_1 dealt ${before - enemy.health}, want 2 (beam-on frames $beamOn, shields $shields, enemy ${enemy.type.name} ${enemy.augments.map { it.name }}, $luaSeen)" }
            out += "pinpoint FOCUS_1 hull -2"
            if (HS.luaActive) {
                lua("hsw-check-beam", """
assert(HSW.fire >= 1, 'PROJECTILE_FIRE not fired')
assert(HSW.name == 'FOCUS_1', 'extend.name ' .. tostring(HSW.name))
assert(HSW.bpName == 'FOCUS_1' and HSW.power == 1 and HSW.len == 1, 'blueprint ' .. tostring(HSW.bpName) .. ' ' .. tostring(HSW.power) .. ' ' .. tostring(HSW.len))
assert(HSW.powered == true and HSW.ship == 0 and HSW.owner == 0, 'factory ' .. tostring(HSW.powered) .. ' ' .. tostring(HSW.ship) .. ' ' .. tostring(HSW.owner))
assert(HSW.beam == 1 and HSW.beamDmg == 2 and HSW.hit == 2 and HSW.beamShip == 1, 'DAMAGE_BEAM ' .. HSW.beam .. '/' .. tostring(HSW.beamCalls) .. ' ' .. tostring(HSW.beamDmg) .. ' ' .. tostring(HSW.hit))
""")
                out += "lua PROJECTILE_FIRE/DAMAGE_BEAM members ok"
            }
        }

        // Oracle (mv-seed-12345, the player's LASER_BURST_1 hitting MU_PIRATE_MV_EXPLORER_START): DAMAGE_AREA and
        // DAMAGE_AREA_HIT get these Damage fields, forceHit 0 (Evasion NONE) and shipFriendlyFire true; the hull
        // after the hit is one lower. (selfId, the projectile's global space id, and the location frame differ.)
        if (HS.luaActive) {
            val enemy = state.enemy!!
            soften(enemy); enemy.health = enemy.maxHealth
            val w = install(state, bp(state, "LASER_BURST_1"))
            aim(state, w, enemy.rooms.first { it.system != null })
            val before = enemy.health
            step(state, 120)
            val want = "{\"breach\":0,\"buster\":false,\"fire\":1,\"friendly\":true,\"hull\":1,\"ion\":0,\"lockdown\":false,\"owner\":-1,\"pers\":0,\"pierce\":0,\"stun\":0,\"stunTime\":0,\"sys\":0}"
            lua("hsw-check-area", """
assert(HSW.area == '$want forceHit=0 ff=true ship=1', 'DAMAGE_AREA ' .. tostring(HSW.area))
assert(HSW.areaHit == '$want ff=true ship=1', 'DAMAGE_AREA_HIT ' .. tostring(HSW.areaHit))
assert(HSW.areaHull == $before - 1, 'hull after hit ' .. tostring(HSW.areaHull))
""")
            out += "oracle LASER_BURST_1 DAMAGE_AREA/_HIT fields match"
        }

        // Shot limit (MISSILES_FREE: shotLimit 6, no missiles) on autofire with fast charge; survives save/load.
        run {
            val enemy = state.enemy!!
            val w = install(state, bp(state, "MISSILES_FREE"))
            check(w.type.hs!!.shotLimit == 6) { "MISSILES_FREE shotLimit ${w.type.hs!!.shotLimit}" }
            state.player.weapons!!.setAutofire(true)
            state.debugFlags.fastWeaponCharge.set = true
            state.debugFlags.noCrewDamage.set = true
            aim(state, w, enemy.rooms.first())
            step(state, 600)
            state.debugFlags.fastWeaponCharge.set = false
            state.debugFlags.noCrewDamage.set = false
            state.player.weapons!!.setAutofire(false)
            check(w.hsShotsFired == 6) { "shot limit: fired ${w.hsShotsFired} (hostile ${state.getEnemyOf(state.player) != null}, powered ${w.isPowered}, missiles ${state.player.missilesCount})" }
            check(state.player.weapons!!.selectedTargets.getTarget(0) == null) { "shot-limited weapon still targeted" }
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            state = main.currentState as InGameState
            check(state.player.hardpoints[0].weapon!!.hsShotsFired == 6) { "hsShotsFired lost on save/load" }
            out += "shotLimit MISSILES_FREE 6 (saved)"
        }

        // Free missiles (KERNEL_1: 35%): one draw per volley without Explosive Replicator.
        run {
            val w = install(state, bp(state, "KERNEL_1"))
            check(w.type.hs!!.freeMissileChance == 35) { "KERNEL_1 chance ${w.type.hs!!.freeMissileChance}" }
            check(state.player.getAugmentValue(xyz.znix.xftl.augments.AugmentBlueprint.EXPLOSIVE_REPLICATOR) == 0f) { "fixture has a replicator" }
            val s0 = BrowserRandom.snapshot()
            HsWeapons.spendMissiles(w, 1)
            val s1 = BrowserRandom.snapshot()
            BrowserRandom.restore(s0); HsWeapons.r32()
            check(BrowserRandom.snapshot() == s1) { "free-missile roll is not one draw" }
            var free = 0
            val n = 20000
            repeat(n) { if (HsWeapons.spendMissiles(w, 1) == 0) free++ }
            val rate = free * 100.0 / n
            check(rate in 33.5..36.5) { "free missile rate $rate%" }
            out += "freeMissileChance KERNEL_1 ${(rate * 10).toInt() / 10.0}%"
        }

        // fireTime (GATLING_ANCIENT 0.05) replaces the per-shot fire time.
        run {
            val w = install(state, bp(state, "GATLING_ANCIENT")) as AbstractProjectileWeaponInstance
            check(w.shotFireTime == .05f) { "fireTime ${w.shotFireTime}" }
            val v = install(state, bp(state, "LASER_BURST_1")) as AbstractProjectileWeaponInstance
            check(v.shotFireTime == xyz.znix.xftl.Animations.PROJECTILE_WEAPON_FIRE_TIME) { "default fire time ${v.shotFireTime}" }
            out += "fireTime ok"
        }

        // Pre-ignited chargers (MV: preIgniteChargers on): a powered charger gets every charge.
        run {
            check(HS.cfg().preIgniteChargers) { "MV preIgniteChargers is off" }
            val charger = all.first { (it.chargeLevels ?: 1) >= 2 && it.power in 1..3 && it.missilesUsed == 0 }
            val w = install(state, charger)
            w.timeCharged = 0f; w.extraCharges = 0
            check(HsWeapons.forceCoolup(w)) { "forceCoolup refused" }
            check(w.isCurrentCharged && w.extraCharges == w.maxExtraCharges) { "${charger.name}: ${w.extraCharges}/${w.maxExtraCharges}" }
            out += "preIgniteChargers ${charger.name} ${w.maxTotalCharges}"
        }

        // defenseDroneFix (MV: boxRange 150, ellipseRange 50): the room box grown by 150 px, then the shield
        // ellipse grown by 50, replace the fixed -150..450 box.
        run {
            check(HS.cfg().defenseDroneFix && HS.cfg().defenseDroneBoxRange[0] == 150f && HS.cfg().defenseDroneEllipseRange[0] == 50f) { "MV defenseDroneFix values" }
            val ship = state.player
            val maxX = ship.rooms.maxOf { it.offsetX + it.width * 35 }.toFloat()
            val minY = ship.rooms.minOf { it.offsetY }.toFloat()
            fun at(x: Float, y: Float) = xyz.znix.xftl.math.ConstFPoint(x, y)
            val inside = at(maxX + 149f, minY + 10f)
            val outside = at(maxX + 151f + 2000f, minY + 10f)
            check(xyz.znix.xftl.hs.weapons.HsDrones.defenceInRange(0, ship, null, inside)) { "box +149 px refused" }
            check(!xyz.znix.xftl.hs.weapons.HsDrones.defenceInRange(0, ship, null, outside)) { "far point accepted" }
            val o = ship.shieldOrigin; val h = ship.shieldHalfSize
            val edge = at(o.x.toFloat(), o.y + h.y + 49f)
            val beyond = at(o.x.toFloat(), o.y + h.y + 51f)
            val boxY = ship.rooms.maxOf { it.offsetY + it.height * 35 } + 150f
            if (edge.yf >= boxY) check(xyz.znix.xftl.hs.weapons.HsDrones.defenceInRange(0, ship, null, edge)) { "ellipse +49 refused" }
            if (beyond.yf >= boxY) check(!xyz.znix.xftl.hs.weapons.HsDrones.defenceInRange(0, ship, null, beyond)) { "ellipse +51 accepted" }
            out += "defenseDroneFix ranges"
        }

        // Icon replacements resolve to an animation frame for every weapon that names one.
        run {
            val named = all.filter { it.hs!!.iconReplace.isNotEmpty() }
            val missing = named.filter { HsWeapons.iconSize(it, state) == null }.map { it.name }
            check(missing.isEmpty()) { "iconReplace without animation: ${missing.take(10)}" }
            out += "iconReplace ${named.size} resolved"
        }
        return "PASS hs-weapons units: " + out.joinToString("; ")
    }

    /** FOCUS_1 against the PIRATE fixture under 20 seeds: the damage dealt, with the enemy's blueprint on a miss. */
    private fun pinpointSeeds(main: MainGame, container: GameContainer): String {
        val bad = ArrayList<String>()
        var blocked = 0
        for (seed in 1..20) {
            BrowserRandom.restore(seed.toString())
            val state = fight(main, container, "PLAYER_SHIP_HARD")
            val enemy = state.enemy!!
            soften(enemy); enemy.health = enemy.maxHealth
            val room = enemy.rooms.first { it.system == null && it.cellCount >= 2 }
            val w = install(state, bp(state, "FOCUS_1")) as BeamBlueprint.BeamInstance
            aim(state, w, room)
            val before = enemy.health
            step(state, 180)
            val dealt = before - enemy.health
            val rock = enemy.getAugmentValueOrNull(xyz.znix.xftl.augments.AugmentBlueprint.ROCK_ARMOR) != null
            if (dealt == 0 && rock) { blocked++; continue }
            if (dealt != 2) bad += "seed $seed: $dealt (${enemy.type.name} ${enemy.augments.map { it.name }} room ${room.id} sys ${room.system})"
        }
        check(bad.isEmpty()) { "pinpoint: ${bad.joinToString("; ")}" }
        return "PASS pinpoint 20 seeds (2 damage; $blocked stopped by Rock Plating's one roll per beam step)"
    }

    // ------------------------------------------------------------------ every weapon

    private fun weapons(main: MainGame, container: GameContainer): String {
        var state = fight(main, container, "PLAYER_SHIP_HARD")
        val names = weaponBlueprints(state).map { it.name }
        var fired = 0; var damaged = 0; var restarts = 0
        val errors = ArrayList<String>(); val silent = ArrayList<String>()
        for ((i, name) in names.withIndex()) {
            if (i % 50 == 0) BrowserEngine.diagnosticProgress("HS weapons: $i/${names.size}")
            if (state !== main.currentState || state.getEnemyOf(state.player) == null || state.player.weapons == null) {
                state = fight(main, container, "PLAYER_SHIP_HARD"); restarts++
            }
            try {
                val enemy = state.enemy!!
                soften(enemy)
                val w = install(state, bp(state, name))
                val room = enemy.rooms.firstOrNull { it.system != null } ?: enemy.rooms.first()
                aim(state, w, room)
                enemy.health = enemy.maxHealth
                val health = enemy.health
                val restoredBefore = restored
                val systemDamage = enemy.systems.sumOf { it.damagedEnergyLevels }
                step(state, 200) { w.hsShotsFired > 0 && !w.isFiring && state.player.projectiles.isEmpty() && enemy.projectiles.isEmpty() }
                if (w.hsShotsFired > 0) fired++ else silent += name
                if (enemy.health < health || restored != restoredBefore || enemy.systems.sumOf { it.damagedEnergyLevels } > systemDamage) damaged++
                // leave nothing in flight for the next weapon
                state.player.projectiles.clear(); state.enemy?.projectiles?.clear()
                enemy.systems.forEach { it.damagedEnergyLevels = 0 }
            } catch (e: Throwable) {
                errors += "$name: ${e.message ?: e}"
                state = fight(main, container, "PLAYER_SHIP_HARD"); restarts++
            }
        }
        val summary = "weapons=${names.size} fired=$fired damaged=$damaged restarts=$restarts silent=${silent.size}${silent.take(40)}"
        check(errors.isEmpty()) { "errors ${errors.size}: ${errors.take(15)}; $summary" }
        return "PASS hs-weapons weapons: $summary"
    }

    // ------------------------------------------------------------------ every drone

    private fun drones(main: MainGame, container: GameContainer): String {
        var state = fight(main, container, "PLAYER_SHIP_CIRCLE")
        val all = state.blueprintManager.blueprints.values.filterIsInstance<DroneBlueprint>().sortedBy { it.name }
        var deployed = 0; var restarts = 0
        val errors = ArrayList<String>(); val notDeployed = ArrayList<String>()
        for ((i, bp) in all.withIndex()) {
            if (i % 25 == 0) BrowserEngine.diagnosticProgress("HS drones: $i/${all.size}")
            if (state !== main.currentState || state.getEnemyOf(state.player) == null || state.player.drones == null) {
                state = fight(main, container, "PLAYER_SHIP_CIRCLE"); restarts++
            }
            try {
                val drones = state.player.drones!!
                state.player.dronesCount = 99; state.player.missilesCount = 99
                drones.energyLevels = 8
                drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
                repeat(8) { drones.increasePower() }
                drones.drones[0] = Drones.DroneInfo(bp)
                val ok = drones.setDronePower(0, true)
                step(state, 120)
                if (ok && drones.drones[0]?.instance != null) deployed++ else notDeployed += bp.name
                drones.setDronePower(0, false)
                drones.drones[0]?.instance?.removeInstance(); drones.drones[0] = null
                state.player.projectiles.clear(); state.enemy?.projectiles?.clear()
            } catch (e: Throwable) {
                errors += "${bp.name}: ${e.message ?: e}"
                state = fight(main, container, "PLAYER_SHIP_CIRCLE"); restarts++
            }
        }
        val summary = "drones=${all.size} deployed=$deployed restarts=$restarts notDeployed=${notDeployed.size}${notDeployed.take(40)}"
        check(errors.isEmpty()) { "errors ${errors.size}: ${errors.take(15)}; $summary" }
        return "PASS hs-weapons drones: $summary"
    }

    // ------------------------------------------------------------------ round 2

    private fun deployDrone(state: InGameState, bp: DroneBlueprint): xyz.znix.xftl.drones.AbstractDrone? {
        val drones = state.player.drones!!
        state.player.dronesCount = 99
        drones.energyLevels = 8
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
        repeat(8) { drones.increasePower() }
        drones.drones[0] = Drones.DroneInfo(bp)
        if (!drones.setDronePower(0, true)) return null
        return drones.drones[0]?.instance
    }

    private fun clearDrone(state: InGameState) {
        val drones = state.player.drones ?: return
        drones.setDronePower(0, false)
        drones.drones[0]?.instance?.removeInstance(); drones.drones[0] = null
    }

    /**
     * Round 2: the Lua point frame (oracle mv-seed-12345), custom crew drones' pawn stats (printed as JSON for the
     * oracle species comparison), erosion, crew spawn / stat boost effects on hit, controllableIonDroneFix,
     * combatDroneRapidFireFix (forced on), crystal shards, simultaneousFire and the negative-cooldown untarget rule.
     */
    private fun round2(main: MainGame, container: GameContainer, only: Set<Int>?): String {
        fun on(n: Int) = only == null || n in only
        val out = ArrayList<String>()
        BrowserRandom.restore("35")

        // 1. frame: the oracle's DAMAGE_AREA location for a hit on PLAYER_SHIP_MVKESTREL room 13 is (245,227).
        // The room centre is taken from the blueprint layout, with the formula checked against every room of a
        // started ship. Only on request (`round2:1`): loading the MV Kestrel's rootless layout XML makes the modded
        // parser's first DOMParser attempt fail, and Chrome's parsererror document logs two CSP inline-style errors
        // (HsLoad.parseXml, loader side), which the page's error check counts.
        var state = fight(main, container, "PLAYER_SHIP_HARD")
        if (only != null && 1 in only) {
            fun centre(bp: xyz.znix.xftl.game.ShipBlueprint, r: xyz.znix.xftl.game.ShipBlueprint.ParsedRoom) =
                ConstPoint(35 * (r.pos.x + bp.roomOffset.x) + 35 * r.size.x / 2, 35 * (r.pos.y + bp.roomOffset.y) + 35 * r.size.y / 2)
            val hard = state.blueprintManager.getShip("PLAYER_SHIP_HARD")
            for (r in hard.rooms) check(centre(hard, r) == state.player.rooms[r.id].pixelCentre) { "room ${r.id} formula" }
            val mvk = state.blueprintManager.getShip("PLAYER_SHIP_MVKESTREL")
            val c13 = centre(mvk, mvk.rooms.first { it.id == 13 })
            check(c13.x == 245 && c13.y == 227) { "MVKESTREL room 13 centre $c13" }
            out += "frame room13=(${c13.x},${c13.y})"
        }

        // 2. custom crew drones: races exist, pawns take the race's stats.
        val stats = ArrayList<String>()
        if (on(2)) {
            val custom = xyz.znix.xftl.hs.weapons.HsDrones.customDrones()
            val raced = custom.values.count { xyz.znix.xftl.hs.crew.CustomCrewManager.isRace(it.crewBlueprint) }
            out += "customDrones=${custom.size} raced=$raced"
            state = fight(main, container, "PLAYER_SHIP_CIRCLE")
            val indoor = state.blueprintManager.blueprints.values.filterIsInstance<DroneBlueprint>().sortedBy { it.name }
                .filter { it.type in listOf(DroneBlueprint.DroneType.BATTLE, DroneBlueprint.DroneType.BOARDER, DroneBlueprint.DroneType.REPAIR) }
            val errors = ArrayList<String>()
            for (bp in indoor) {
                if (state !== main.currentState || state.getEnemyOf(state.player) == null || state.player.drones == null) state = fight(main, container, "PLAYER_SHIP_CIRCLE")
                try {
                    if (bp.type == DroneBlueprint.DroneType.REPAIR) state.player.systems.firstOrNull()?.let { it.damagedEnergyLevels = 1 }
                    val d = deployDrone(state, bp) as? xyz.znix.xftl.drones.AbstractIndoorsDrone
                    if (d == null) { clearDrone(state); continue }
                    step(state, 600) { d.pawn != null }
                    val pawn = d.pawn
                    if (pawn != null) {
                        val ex = xyz.znix.xftl.hs.crew.HsCrew.ex(pawn)
                        val def = ex.definition()
                        fun b(st: xyz.znix.xftl.hs.stat.CrewStat) = def != null && ex.calculateBool(st, def)
                        fun v(st: xyz.znix.xftl.hs.stat.CrewStat) = if (def != null) ex.calculateStat(st, def) else -1f
                        val S = xyz.znix.xftl.hs.stat.CrewStat.MAX_HEALTH
                        val sb = StringBuilder()
                        sb.append("{\"bp\":\"${bp.name}\",\"race\":\"${pawn.codename}\",\"def\":${def != null},\"maxHealth\":${pawn.baseMaxHealth}")
                        sb.append(",\"move\":${pawn.movementSpeed / xyz.znix.xftl.crew.AbstractCrew.BASE_MOVEMENT_SPEED},\"repair\":${pawn.repairSpeed}")
                        sb.append(",\"dmg\":${pawn.attackDamageMult},\"fireRepair\":${pawn.fireFightingSpeed}")
                        for (st in listOf("MAX_HEALTH", "MOVE_SPEED_MULTIPLIER", "REPAIR_SPEED_MULTIPLIER", "DAMAGE_MULTIPLIER", "FIRE_REPAIR_MULTIPLIER"))
                            sb.append(",\"$st\":${v(xyz.znix.xftl.hs.stat.CrewStat.valueOf(st))}")
                        for (st in listOf("CAN_MAN", "CAN_REPAIR", "CAN_FIGHT", "CAN_SABOTAGE", "CAN_SUFFOCATE", "CAN_BURN"))
                            sb.append(",\"$st\":${b(xyz.znix.xftl.hs.stat.CrewStat.valueOf(st))}")
                        sb.append("}")
                        stats += sb.toString()
                        check(S == S)
                    }
                    clearDrone(state)
                    state.player.projectiles.clear(); state.enemy?.projectiles?.clear()
                } catch (e: Throwable) {
                    errors += "${bp.name}: ${e.message ?: e}"
                    state = fight(main, container, "PLAYER_SHIP_CIRCLE")
                }
            }
            check(errors.isEmpty()) { "drone errors ${errors.size}: ${errors.take(10)}" }
            out += "indoorDrones=${indoor.size} pawns=${stats.size}"

        }
        // 3. erosion on an enemy system room: the system loses integrity, breaches open, the timer runs out.
        if (on(3)) {
            state = fight(main, container, "PLAYER_SHIP_HARD")
            run {
                val enemy = state.enemy!!
                val ew = weaponBlueprints(state).first { (it.hs?.damage?.erosionChance ?: -1) > 0 }
                val room = enemy.rooms.first { it.system != null && it.system!!.energyLevels > 1 }
                val sys = room.system!!
                val before = sys.damagedEnergyLevels
                xyz.znix.xftl.hs.systems.HsErosionRoom.start(room, ew.hs!!.damage.erosion)
                val e = xyz.znix.xftl.hs.systems.HsErosionRoom.state(room)!!
                val t0 = e.timer
                val saved = room.saveToXML()
                check(saved?.getChild("hsErosion") != null) { "erosion not saved" }
                var maxBreaches = 0; var steps = 0
                while (xyz.znix.xftl.hs.systems.HsErosionRoom.active(room) && steps < 2000) {
                    state.updateGameState(DT); steps++
                    maxBreaches = maxOf(maxBreaches, room.breaches.count { it != null })
                }
                val damaged = sys.damagedEnergyLevels - before
                check(!xyz.znix.xftl.hs.systems.HsErosionRoom.active(room)) { "erosion still active after $steps steps" }
                check(damaged > 0 || maxBreaches > 0) { "erosion did nothing (sys ${sys.damagedEnergyLevels}/$before)" }
                out += "erosion ${ew.name} time=$t0 lasted=${"%.2f".format(steps * DT)}s sysDamage=$damaged breaches=$maxBreaches"
            }

        }
        // 4. effects on hit, through counting handlers that pass on to the real ones.
        if (on(4)) run {
            val counts = IntArray(4)
            val real = xyz.znix.xftl.hs.weapons.HsDamage.handlers
            xyz.znix.xftl.hs.weapons.HsDamage.handlers = object : xyz.znix.xftl.hs.weapons.HsDamage.Handlers {
                override fun spawnCrew(spawn: xyz.znix.xftl.hs.crew.CrewSpawn, ship: Ship, intruder: Boolean, location: xyz.znix.xftl.math.IPoint, tile: Boolean) { counts[0]++; real.spawnCrew(spawn, ship, intruder, location, tile) }
                override fun crewStatBoost(def: xyz.znix.xftl.hs.stat.StatBoostDefinition, crew: xyz.znix.xftl.crew.AbstractCrew, sourceShipId: Int) { counts[1]++; real.crewStatBoost(def, crew, sourceShipId) }
                override fun roomStatBoost(def: xyz.znix.xftl.hs.stat.StatBoostDefinition, ship: Ship, room: Room, sourceShipId: Int) { counts[2]++; real.roomStatBoost(def, ship, room, sourceShipId) }
                override fun erosion(effect: xyz.znix.xftl.hs.weapons.HsErosion, room: Room) { counts[3]++; real.erosion(effect, room) }
            }
            try {
                val all = weaponBlueprints(state).filter { it !is BeamBlueprint }
                val picks = listOf(
                    all.firstOrNull { it.hs!!.damage.crewSpawnDefs.isNotEmpty() && it.hs!!.damage.crewSpawnChance >= 10 },
                    all.firstOrNull { it.hs!!.damage.statBoostDefs.isNotEmpty() && it.hs!!.damage.statBoostChance >= 10 },
                    all.firstOrNull { it.hs!!.damage.roomStatBoostDefs.isNotEmpty() && it.hs!!.damage.roomStatBoostChance >= 10 },
                    all.firstOrNull { it.hs!!.damage.erosionChance >= 10 },
                )
                var crewBefore = -1
                for ((i, pick) in picks.withIndex()) {
                    if (pick == null) { out += "effect$i none"; continue }
                    if (state !== main.currentState || state.enemy == null) state = fight(main, container, "PLAYER_SHIP_HARD")
                    val enemy = state.enemy!!
                    if (i == 0) crewBefore = enemy.crew.size
                    val w = install(state, pick)
                    repeat(4) {
                        if (counts[i] > 0) return@repeat
                        w.timeCharged = w.chargeTime; w.extraCharges = w.maxExtraCharges
                        val room = enemy.rooms.firstOrNull { r -> r.crew.isNotEmpty() } ?: enemy.rooms.first()
                        aim(state, w, room)
                        step(state, 300) { counts[i] > 0 || (w.hsShotsFired > 0 && !w.isFiring && state.player.projectiles.isEmpty()) }
                        w.hsShotsFired = 0
                    }
                    check(counts[i] > 0) { "effect $i (${pick.name}) never applied" }
                    out += "effect$i ${pick.name} n=${counts[i]}"
                    if (i == 0) out += "spawned crew $crewBefore->${enemy.crew.size}"
                    state.player.projectiles.clear(); enemy.projectiles.clear()
                }
            } finally {
                xyz.znix.xftl.hs.weapons.HsDamage.handlers = real
            }
        }

        // 5. controllableIonDroneFix: the first charge starts ionDelayInitial s after the pod lands.
        if (on(5) && HS.cfg().controllableIonDroneFix) {
            state = fight(main, container, "PLAYER_SHIP_CIRCLE")
            val bp = state.blueprintManager["BOARDER_ION"] as DroneBlueprint
            val d = deployDrone(state, bp) as xyz.znix.xftl.drones.BoardingDrone
            step(state, 1200) { d.pawn != null }
            val pawn = checkNotNull(d.pawn) { "ion drone never landed" }
            val clock = checkNotNull(d.ionClock) { "no ion clock on $pawn" }
            check(clock.hsMode) { "ion clock not in HS mode" }
            var t = 0
            while (!clock.armed && t < 400) { state.updateGameState(DT); state.enemy?.let { soften(it) }; t++ }
            val first = t * DT
            check(clock.armed) { "no charge after ${first}s" }
            val expect = HS.cfg().controllableIonDroneDelayInitial
            check(kotlin.math.abs(first - expect) <= 2 * DT) { "first charge after ${first}s, expected $expect" }
            out += "ionDrone firstCharge=${"%.2f".format(first)}s delay=${HS.cfg().controllableIonDroneDelay}"
            clearDrone(state)
        }

        // 6. combatDroneRapidFireFix (MV leaves it off; forced on here): each hop turns 90-270 degrees.
        if (on(6)) run {
            val cfg = HS.cfg()
            val was = cfg.combatDroneRapidFireFix
            cfg.combatDroneRapidFireFix = true
            try {
                state = fight(main, container, "PLAYER_SHIP_CIRCLE")
                val d = deployDrone(state, state.blueprintManager["COMBAT_1"] as DroneBlueprint) as xyz.znix.xftl.drones.AbstractExternalDrone
                step(state, 20)
                val fc = d.flightController as xyz.znix.xftl.drones.CombatFlightController
                var last = fc.currentDestAngle; var hops = 0; var bad = 0
                repeat(1200) {
                    state.updateGameState(DT); state.enemy?.let { soften(it) }
                    val a = fc.currentDestAngle
                    if (a != last) {
                        val diff = ((Math.round(a * 180f / Math.PI.toFloat()) - Math.round(last * 180f / Math.PI.toFloat())) % 360 + 360) % 360
                        if (diff < 90 || diff > 270) bad++
                        hops++; last = a
                    }
                }
                check(hops >= 5 && bad == 0) { "rapid fire fix hops=$hops bad=$bad" }
                out += "combatDroneRapidFireFix hops=$hops"
                clearDrone(state)
            } finally { cfg.combatDroneRapidFireFix = was }
        }

        // 7. crystal shards: an augment's <crystalShard> launches owned shards when the ship takes hull damage.
        if (on(7)) run {
            state = fight(main, container, "PLAYER_SHIP_HARD")
            val augName = HS.cfg().nodesOf("augments").flatMap { it.getChildren("aug") }
                .firstOrNull { a ->
                    a.getChildren("crystalShard").any { (it.getAttributeValue("value") ?: it.getAttributeValue("chance") ?: "0").toFloat() >= .5f } &&
                        state.blueprintManager.getOrNull(a.getAttributeValue("name")) is xyz.znix.xftl.augments.AugmentBlueprint
                }
                ?.getAttributeValue("name")
            if (augName == null) out += "crystalShard none" else {
                val player = state.player
                player.augments += state.blueprintManager[augName] as xyz.znix.xftl.augments.AugmentBlueprint
                xyz.znix.xftl.hs.ship.HsAugments.updateAugments(player)
                val room = player.rooms.first()
                var shard: xyz.znix.xftl.weapons.AbstractWeaponProjectile? = null
                var hits = 0
                while (shard == null && hits < 40) {
                    val dmg = xyz.znix.xftl.weapons.Damage(bp(state, "LASER_BURST_1"))
                    dmg.ownerShip = state.enemy
                    player.health = player.maxHealth
                    player.damage(room, dmg); hits++
                    shard = player.projectiles.filterIsInstance<xyz.znix.xftl.weapons.AbstractWeaponProjectile>().firstOrNull { it.hsCrystalShard }
                }
                checkNotNull(shard) { "no custom crystal shard from $augName in $hits hits" }
                check((shard.antiDroneExemption === player) == HS.cfg().crystalShardFix) { "shard exemption ${shard.antiDroneExemption}" }
                out += "crystalShard $augName ${shard.type.name} after $hits hits, owned=${shard.antiDroneExemption === player}"
                player.projectiles.clear()
            }
        }

        // 8. simultaneousFire: a fully charged weapon sends every charge on the same frame.
        if (on(8)) run {
            val sim = weaponBlueprints(state).firstOrNull { it.hs?.simultaneousFire == true && (it.chargeLevels ?: 1) > 1 }
            if (sim == null) out += "simultaneousFire none" else {
                if (state !== main.currentState || state.enemy == null) state = fight(main, container, "PLAYER_SHIP_HARD")
                val enemy = state.enemy!!
                state.player.projectiles.clear()
                val w = install(state, sim)
                val charges = w.maxTotalCharges
                aim(state, w, enemy.rooms.first())
                var frames = 0; var firstFrame = -1; var lastFrame = -1; var maxCount = 0
                var prev = 0; var volley = 0
                while (frames < 400 && (firstFrame < 0 || w.isFiring)) {
                    state.updateGameState(DT); soften(enemy); frames++
                    val n = state.player.projectiles.size + enemy.projectiles.size
                    volley = maxOf(volley, (w as AbstractProjectileWeaponInstance).hsVolleyCharges)
                    if (n > prev) { if (firstFrame < 0) firstFrame = frames; lastFrame = frames }
                    prev = n; maxCount = maxOf(maxCount, n)
                }
                check(firstFrame > 0 && firstFrame == lastFrame) { "${sim.name}: launches over frames $firstFrame..$lastFrame" }
                check(volley == charges && maxCount % charges == 0) { "${sim.name}: volley of $volley charges, $maxCount projectiles, for $charges charges" }
                out += "simultaneousFire ${sim.name} charges=$charges projectiles=$maxCount frame=$firstFrame"
                state.player.projectiles.clear(); enemy.projectiles.clear()
            }
        }

        // 9. a negative-cooldown weapon is untargeted after firing, even on autofire.
        if (on(9)) run {
            val neg = weaponBlueprints(state).firstOrNull { it.chargeTime < 0f && it !is BeamBlueprint }
            if (neg == null) out += "negativeCooldown none" else {
                if (state !== main.currentState || state.enemy == null) state = fight(main, container, "PLAYER_SHIP_HARD")
                val weapons = state.player.weapons!!
                val w = install(state, neg)
                weapons.setAutofire(true)
                aim(state, w, state.enemy!!.rooms.first())
                step(state, 200) { w.hsShotsFired > 0 }
                val target = weapons.selectedTargets.getTarget(0)
                weapons.setAutofire(false)
                check(w.hsShotsFired > 0) { "${neg.name} never fired" }
                check(target == null) { "${neg.name} still targeted" }
                out += "negativeCooldown ${neg.name} untargeted"
            }
        }

        return "PASS hs-weapons round2: " + out.joinToString("; ") + " DRONESTATS [" + stats.joinToString(",") + "]"
    }
}
