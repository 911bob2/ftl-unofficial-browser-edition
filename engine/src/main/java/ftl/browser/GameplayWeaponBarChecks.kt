package ftl.browser

import xyz.znix.xftl.Constants
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.TouchHud
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance

/**
 * Weapon charge bars and the charge kept by a reload (Android reports: weapons drawn "ready" with the bar about two
 * thirds full, and "green after Continue").
 *
 * The weapon box's charge frame (WeaponBox, 1.6.14 FUN_00507590; the iPad's box draws the same frame at the large
 * size) is `33 * cooldown.second / the longest cooldown.second * cooldownModifier` pixels, where cooldown.second is the
 * charge time scaled by the modifier (ProjectileFactory::SetCooldownModifier), and its bar is that frame scaled by
 * first / second, in the box's status colour unless the weapon is aimed. The box turns the charged colour when
 * chargeLevel > 0 (WeaponBox::StatusColor), that is at a full bar. The oracle (wr-a/b, Multiverse Kestrel) shows the
 * charge, its cooldown second (base x 0.9 when manned) and the charged state kept as they were across Save and quit
 * and Continue.
 */
internal object GameplayWeaponBarChecks {
    private val WEAPONS = listOf("LASER_BURST_2", "LASER_BURST_1", "LASER_HEAVY_1")

    fun run(main: MainGame, container: GameContainer): String {
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            case("fullBars") { fullBars(main, container, manned = false) }
            case("mannedBars") { fullBars(main, container, manned = true) }
            case("reloadKeepsCharge") { reloadKeepsCharge(main, container) }
            case("chargesWithoutEnemy") { chargesWithoutEnemy(main, container) }
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "weapon bars ok: " + results.joinToString(", ")
    }

    private fun fixture(main: MainGame, container: GameContainer, enemy: Boolean = true): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        if (enemy) state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        val ship = state.player
        ship.weapons!!.energyLevels = 8
        ship.purchasedReactorPower = 25
        ship.updateAvailablePower()
        ship.weapons!!.setAutofire(false)
        for ((slot, name) in WEAPONS.withIndex()) {
            val weapon = (state.blueprintManager[name] as AbstractWeaponBlueprint).buildInstance(ship)
            ship.hardpoints[slot].weapon = weapon
        }
        for (slot in WEAPONS.size until ship.hardpoints.size) ship.hardpoints[slot].weapon = null
        ship.cargoUpdated()
        for (hp in ship.hardpoints) hp.weapon?.let { check(ship.weapons!!.setWeaponPower(it, true) && it.isPowered) { "${it.type.name} did not power" } }
        return state
    }

    private fun step(main: MainGame, container: GameContainer, seconds: Float) {
        var left = seconds
        while (left > 0f) {
            main.update(container, 0.05f)
            left -= 0.05f
        }
    }

    private fun man(main: MainGame, container: GameContainer, state: InGameState) {
        val weapons = state.player.weapons!!
        val room = weapons.room!!
        val console = weapons.configuration.computerPoint ?: ConstPoint.ZERO
        if (weapons.getBoostSkillLevel(Skill.WEAPONS) == null) {
            val gunner = room.crew.filterIsInstance<LivingCrew>().firstOrNull() ?: state.player.crew.filterIsInstance<LivingCrew>().first()
            gunner.jumpTo(room, console)
            gunner.nextTargetPos = null
            room.updateCrewInRoom(); state.player.updateCrewReservedSlots()
        }
        step(main, container, 1f)
        check(weapons.getBoostSkillLevel(Skill.WEAPONS) != null) { "Nobody mans the weapons" }
    }

    /** Every weapon fully charged: its bar fills its own frame, the frame follows the native size, the bar takes the box colour. */
    private fun fullBars(main: MainGame, container: GameContainer, manned: Boolean): String {
        val state = fixture(main, container)
        val weapons = state.player.weapons!!
        if (manned) man(main, container, state)
        val mod = weapons.getBoostSkillLevel(Skill.WEAPONS)?.let { 1f - xyz.znix.xftl.systems.Weapons.SKILL_BONUSES[it.ordinal] / 100f } ?: 1f
        val list = state.player.hardpoints.mapNotNull { it.weapon }
        for (w in list) w.timeCharged = w.chargeTime
        val longest = list.maxOf { it.chargeTime * mod }
        val hud = TouchHud(state.shipUI, state)
        val out = ArrayList<String>()
        for ((slot, w) in list.withIndex()) {
            check(w.isCharged) { "${w.type.name} is not charged at a full bar" }
            val expected = (w.chargeTime * mod / longest * 33 * mod).toInt().coerceAtLeast(1)
            val column = hud.chargeColumn(slot, w, Constants.WEAPONS_ITEM_CHARGED)
            check(column.fullBar == expected) { "${w.type.name}: the full bar is ${column.fullBar} px, native $expected (manning x$mod)" }
            check(weapons.chargeBarHeight(w, 33) == expected) { "${w.type.name}: the desktop bar is ${weapons.chargeBarHeight(w, 33)} px, native $expected" }
            check(column.bar == column.fullBar) { "${w.type.name}: a full charge draws ${column.bar} of ${column.fullBar} px" }
            check(column.height == column.fullBar + 2 && column.top == 35 - column.fullBar) {
                "${w.type.name}: the frame is ${column.height} px from ${column.top}, for a ${column.fullBar} px bar: a full bar looks part full"
            }
            check(column.barColour == Constants.WEAPONS_ITEM_CHARGED) { "${w.type.name}: the bar is not in the box's colour" }
            // The closed column draws the same frame at the small size: 22 px for the slowest weapon, a 2 px bar in a
            // 1 px frame, the bar's bottom 24 px down the icon.
            val small = (w.chargeTime * mod / longest * 22 * mod).toInt().coerceAtLeast(1)
            val icon = hud.chargeIcon(slot, w, Constants.WEAPONS_ITEM_CHARGED)
            check(icon.fullBar == small && icon.bar == small) { "${w.type.name}: the closed column's full bar is ${icon.bar} of ${icon.fullBar} px, native $small" }
            check(icon.height == small + 2 && icon.top == 23 - small && icon.barBottom == 24 && icon.barWidth == 2 && icon.left == 1 && icon.width == 4) {
                "${w.type.name}: the closed column's frame is ${icon.width}x${icon.height} from ${icon.left},${icon.top}, bar ${icon.barWidth} px to ${icon.barBottom}"
            }
            out += "${w.type.name} ${column.fullBar}/${icon.fullBar}"
        }
        return (if (manned) "manned " else "") + "full bars " + out.joinToString("/")
    }

    /** A save mid-fight keeps each weapon's charge, charged state and power, and the bar draws the same after the load. */
    private fun reloadKeepsCharge(main: MainGame, container: GameContainer): String {
        var state = fixture(main, container)
        man(main, container, state)
        val list = state.player.hardpoints.mapNotNull { it.weapon }
        list[0].timeCharged = list[0].chargeTime * 0.4f
        list[1].timeCharged = list[1].chargeTime
        state.player.weapons!!.setWeaponPower(list[2], false)
        state.player.weapons!!.selectedTargets.targetRoom(0, state.enemy!!.rooms.first())
        fun snap(s: InGameState): List<String> {
            val hud = TouchHud(s.shipUI, s)
            return s.player.hardpoints.mapNotNull { it.weapon }.mapIndexed { i, w ->
                val c = hud.chargeColumn(i, w, Constants.WEAPONS_ITEM_CHARGED)
                "${w.type.name}:${Math.round(w.timeCharged * 100)}:${w.isCharged}:${w.isPowered}:${c.bar}/${c.fullBar}"
            }
        }
        val before = snap(state)
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        val after = snap(state)
        check(before == after) { "The load changed the weapons: $before -> $after" }
        val charging = state.player.hardpoints[0].weapon!!
        val was = charging.timeCharged
        main.update(container, 0.05f)
        check(charging.timeCharged - was < 0.1f && !charging.isCharged) { "The first frame after the load charged ${was} -> ${charging.timeCharged}" }
        return "reload keeps " + after.joinToString(" ")
    }

    /** With no ship at the beacon a powered weapon charges and turns charged at a full bar, as in the original (oracle wr-c). */
    private fun chargesWithoutEnemy(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container, enemy = false)
        check(state.enemy == null) { "The fixture has a ship" }
        val w = state.player.hardpoints[2].weapon!!
        w.timeCharged = 0f
        step(main, container, w.chargeTime * 0.5f)
        check(w.timeCharged > 0f && !w.isCharged) { "Half way it is ${w.timeCharged}/${w.chargeTime}, charged ${w.isCharged}" }
        step(main, container, w.chargeTime * 0.6f)
        check(w.isCharged) { "It did not charge without an enemy: ${w.timeCharged}/${w.chargeTime}" }
        return "charges without an enemy"
    }
}
