package ftl.browser

import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.systems.Engines
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.BombBlueprint
import kotlin.math.abs

/**
 * Ship systems and weapon types: the manning boost that vanishes on a damaged system (ShipSystem::IsMannedBoost
 * 0x63bbc0), the engine dodge table (0x885ee0), the autopilot dodge factor (ShipManager::GetDodgeFactor 0x49fe90), the
 * AE manning level that only sensors and doors gain (ShipSystem::CheckDlcEnabled 0x63d530), the beam speed
 * (BeamWeapon::OnUpdate 0x438880) and where a missed bomb lands (0x450080).
 */
internal object GameplayParityCombatRulesChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        try {
            val manning = manningBoost(main, container)
            val autopilot = autopilot(main, container)
            val beam = beamSpeed(main, container)
            val bomb = missedBomb(main, container)
            // These two leave the native draws as they found them, so the later steps see the same sequence.
            val native = NativeRandom.snapshot()
            val ion = try { shieldIonRepower(main, container) } finally { NativeRandom.restore(native) }
            val dodge = try {
                BrowserGameplayParityDiagnostics.vanillaOnly("dodge table") { dodgeTable(main, container) }
            } finally { NativeRandom.restore(native) }
            return "combat rules ok: $manning, $autopilot, $beam, $bomb, $ion, $dodge"
        } finally {
            BrowserRandom.restore(rng)
        }
    }

    /**
     * An automated ship counts every undamaged system as manned at the base level, so it stands in for a crewmember.
     * Damage takes the boost away at once, the engine dodge follows the table, and a ninth (battery) power level
     * reads past the end of the eight entries as zero.
     */
    private fun manningBoost(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        // A start beacon in a nebula caps the sensors at 0 (StatusEffect::GetNebulaEffect), which takes the bonus level.
        state.currentBeacon.powerLimitEffects.clear()
        player.updateScriptedPowerLimits()
        player.isAutomated = true
        try {
            val engines = player.engines!!
            val shields = player.shields!!
            val weapons = player.weapons!!

            val boosted = listOf(
                "engines" to (engines to Skill.ENGINES),
                "shields" to (shields to Skill.SHIELDS),
                "weapons" to (weapons to Skill.WEAPONS)
            )
            for ((label, pair) in boosted) {
                check(pair.first.getBoostSkillLevel(pair.second) == SkillLevel.BASE) { "Undamaged $label is not manned" }
            }

            val level = engines.powerSupplied
            if (level > 0) {
                val expected = (Engines.EVASIONS.getOrNull(level - 1) ?: 0) + Engines.SKILL_BONUSES[0]
                check(engines.evasion == expected) { "Manned engine dodge ${engines.evasion} vs $expected" }
            }

            // IsMannedBoost: damaged means unmanned for every boost.
            engines.damagedEnergyLevels = 1
            shields.damagedEnergyLevels = 1
            weapons.damagedEnergyLevels = 1
            for ((label, pair) in boosted) {
                check(pair.first.getBoostSkillLevel(pair.second) == null) { "Damaged $label still gets a boost" }
            }
            engines.damagedEnergyLevels = 0
            shields.damagedEnergyLevels = 0
            weapons.damagedEnergyLevels = 0

            // The dodge table: five a level, flattening out, and nothing past the eighth entry.
            check(Engines.EVASIONS.toList() == listOf(5, 10, 15, 20, 25, 28, 31, 35)) { "Dodge table changed" }

            // Only sensors and doors get the extra AE level when manned.
            val sensors = player.sensors!!
            val piloting = player.piloting!!
            check(sensors.effectivePower == sensors.undamagedEnergy + 1) { "Manned sensors lack the bonus level" }
            check(piloting.effectivePower == piloting.undamagedEnergy) { "Manned piloting gained a bonus level" }
            player.doorsSystem?.let {
                check(it.effectivePower == it.undamagedEnergy + 1) { "Manned doors lack the bonus level" }
            }
        } finally {
            player.isAutomated = false
        }
        return "manning boost, dodge table, AE bonus levels"
    }

    /**
     * An unmanned piloting system flies on autopilot: nothing at power 1, half the evasion at 2 and four fifths from
     * 3. A crewmember standing at the console is always worth the full amount, so the exact table only applies when
     * nobody is.
     */
    private fun autopilot(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val piloting = player.piloting!!
        val expected = listOf(0f, 0.5f, 0.8f, 0.8f)
        val crewed = piloting.room!!.crew.isNotEmpty()
        for (level in 1..3) {
            piloting.energyLevels = level
            piloting.damagedEnergyLevels = 0
            val multiplier = piloting.evasionMultiplier
            if (crewed) {
                check(multiplier == 1f || multiplier == expected[level - 1]) { "Autopilot at level $level gave $multiplier" }
            } else {
                check(multiplier == expected[level - 1]) { "Autopilot at level $level gave $multiplier" }
            }
        }

        // A broken system gives nothing, however it is manned.
        piloting.damagedEnergyLevels = piloting.energyLevels
        check(piloting.evasionMultiplier == 0f) { "Broken piloting still dodges" }
        piloting.damagedEnergyLevels = 0
        return "autopilot dodge factor"
    }

    /**
     * Ion on the shields (ShipSystem::IonDamage 0x63d830, CheckForRepower1 0x63ac40; HS oracle ps-c1-pw and -pw2): the
     * hit takes single bars and the odd one left stays powered while the lock runs; when it is over the bars climb back
     * to the player's power. Damage instead drops to the last whole layer, and the repair climbs back too.
     */
    private fun shieldIonRepower(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val shields = player.shields!!
        for (system in player.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>()) {
            if (system !== shields) repeat(8) { system.decreasePower() }
        }
        shields.energyLevels = 4
        shields.damagedEnergyLevels = 0
        player.updateAvailablePower()
        repeat(2) { shields.increasePower() }
        check(shields.powerSelected == 4 && shields.targetPower == 4) { "Shields not on 4: ${shields.powerSelected}" }

        shields.dealDamage(0, 1)
        check(shields.powerSelected == 3 && shields.powerSupplied == 3 && shields.suppliedShieldBars == 1 && shields.targetPower == 4) {
            "One ion on 4 shield bars: power ${shields.powerSelected}/${shields.powerSupplied} target ${shields.targetPower}"
        }
        repeat(20) { state.updateGameState(.05f) }
        check(shields.powerSelected == 3 && shields.isIonised) { "The odd bar went while the lock runs: ${shields.powerSelected}" }
        repeat(100) { state.updateGameState(.05f) }
        check(!shields.isIonised && shields.powerSelected == 4 && shields.targetPower == 4) {
            "After the ion: power ${shields.powerSelected} target ${shields.targetPower}"
        }

        // a lone pair: one ion leaves the single bar, and it comes back
        shields.energyLevels = 2
        player.updateAvailablePower()
        shields.targetPower = shields.powerSelected
        check(shields.powerSelected == 2) { "Shields not on 2: ${shields.powerSelected}" }
        shields.dealDamage(0, 1)
        repeat(20) { state.updateGameState(.05f) }
        check(shields.powerSelected == 1 && shields.activeShields == 0) { "One ion on 2 shield bars: ${shields.powerSelected}" }
        repeat(100) { state.updateGameState(.05f) }
        check(shields.powerSelected == 2 && shields.targetPower == 2) { "After the ion on 2: ${shields.powerSelected} target ${shields.targetPower}" }

        // damage: down to the whole layer; the repair brings the player's power back
        shields.energyLevels = 4
        player.updateAvailablePower()
        repeat(2) { shields.increasePower() }
        shields.addDamage(1)
        check(shields.powerSelected == 2 && shields.targetPower == 4) { "One damage on 4: power ${shields.powerSelected} target ${shields.targetPower}" }
        shields.repair(1f)
        check(shields.powerSelected == 4 && shields.targetPower == 4) { "Repaired: power ${shields.powerSelected} target ${shields.targetPower}" }
        return "shield ion and damage repower"
    }

    /**
     * ShipManager::GetDodgeFactor (1.6.14 0x49fe90): a manned, working cockpit gives the pilot's bonus plus the engines'
     * (their manning bonus and the table entry of their effective power, EngineSystem 0x63f8e0); without a pilot the
     * autopilot gives the engines' part times 0.5 at piloting power 2 and 0.8 from 3, nothing below. The rows are every
     * engine power with no, base, trained and mastered skills (HS oracle ps-c1-ev holds the native rows).
     */
    private fun dodgeTable(main: MainGame, container: GameContainer): String {
        val table = listOf(5, 10, 15, 20, 25, 28, 31, 35)
        check(Engines.EVASIONS.toList() == table) { "Dodge table changed" }
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val engines = player.engines!!
        val piloting = player.piloting!!
        for (system in player.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>()) {
            if (system !== engines) repeat(8) { system.decreasePower() }
        }
        player.purchasedReactorPower = 25
        engines.energyLevels = 8
        engines.damagedEnergyLevels = 0
        repeat(8) { engines.decreasePower() }
        player.updateAvailablePower()
        var rows = 0
        for (power in 1..8) {
            engines.increasePower()
            check(engines.powerSupplied == power) { "Engines on ${engines.powerSupplied}, not $power" }
            val p = piloting.getBoostSkillLevel(xyz.znix.xftl.crew.Skill.PILOTING)?.let { xyz.znix.xftl.systems.Piloting.SKILL_BONUSES[it.ordinal] } ?: 0
            val e = engines.getBoostSkillLevel(xyz.znix.xftl.crew.Skill.ENGINES)?.let { Engines.SKILL_BONUSES[it.ordinal] } ?: 0
            val engine = e + table[power - 1]
            val expected = when {
                piloting.hasPilot -> p + engine
                piloting.undamagedEnergy >= 3 -> (engine * 0.8f).toInt()
                piloting.undamagedEnergy == 2 -> (engine * 0.5f).toInt()
                else -> 0
            }
            check(player.dodgeFactor == expected) { "Engine power $power: dodge ${player.dodgeFactor}, native rule $expected" }
            rows++
        }
        return "dodge table ($rows engine powers)"
    }

    /** Beams move at their blueprint speed times the native speed factor of 16 per second. */
    private fun beamSpeed(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var checked = 0
        for (name in listOf("BEAM_1", "BEAM_2", "BEAM_3")) {
            val beam = state.blueprintManager.getOrNull(name) as BeamBlueprint? ?: continue
            val perSecond = beam.speedPixelsPerSecond
            check(perSecond > 0f && perSecond % 16f == 0f) { "$name moves at $perSecond, not a multiple of 16" }
            check(abs(beam.fireDuration - beam.length / perSecond) < 1e-4f) { "$name fire duration" }
            if (name == "BEAM_1") check(perSecond == 48f) { "BEAM_1 moves at $perSecond, not 48" }
            checked++
        }
        check(checked > 0) { "No beam weapon to check" }
        return "beam speed x16 ($checked beams)"
    }

    /**
     * A missed bomb starts in the middle of the aimed room and takes 117 pixel steps in one random cardinal direction
     * until it is outside every room, so the answer is an axis-aligned offset from the room centre and never a point
     * inside the ship.
     */
    private fun missedBomb(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val directions = HashSet<Int>()
        for (room in ship.rooms) {
            for (direction in 0 until 4) {
                val centre = room.pixelCentre
                val landing = xyz.znix.xftl.weapons.BombProjectile.missLanding(ship,
                    xyz.znix.xftl.math.ConstFPoint(centre.x.toFloat(), centre.y.toFloat()), direction)
                val x = landing.xf
                val y = landing.yf
                check(ship.rooms.none { it.containsPixelInclusive(x.toInt(), y.toInt()) }) { "A missed bomb landed inside the ship" }

                val dx = x.toInt() - centre.x
                val dy = y.toInt() - centre.y
                check((dx == 0) != (dy == 0)) { "A missed bomb left the room centre on both axes: $dx, $dy" }
                val offset = if (dx != 0) dx else dy
                check(offset % 117 == 0) { "Offset $offset is not a whole number of steps" }
                directions += if (dx != 0) (if (dx < 0) 2 else 3) else (if (dy < 0) 0 else 1)
            }
        }
        check(directions.size > 1) { "Every missed bomb went the same way" }
        return "missed bomb landing (${directions.size} directions)"
    }
}
