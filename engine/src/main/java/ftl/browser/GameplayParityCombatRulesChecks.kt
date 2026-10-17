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
            return "combat rules ok: $manning, $autopilot, $beam, $bomb"
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
        val type = state.blueprintManager["BOMB_1"] as BombBlueprint
        val directions = HashSet<Int>()
        for (room in ship.rooms) {
            repeat(4) {
                val animation = state.animations[type.projectile!!].startSingle(state, 0.5f, true)
                val bomb = BombBlueprint.FiredBomb(type, room, animation, true, false)
                val x = bomb.position.x
                val y = bomb.position.y
                val centre = room.pixelCentre
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
