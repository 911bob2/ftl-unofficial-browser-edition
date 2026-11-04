package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.SaveProfile
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.Damage

/**
 * Round 17 (Android weapon reports): weapons that would not take power, a queued shot that did not fire, the ship
 * unlock popup with no image, and the neutral ship after a reload. Every case runs on its own; the failures are listed
 * together.
 */
internal object GameplayWeapons17Checks {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            case("armedDepowered") { armedDepowered(main, container) }
            case("powerAfterIon") { powerAfterIon(main, container) }
            case("powerAfterRepair") { powerAfterRepair(main, container) }
            case("powerInStorm") { powerInStorm(main, container) }
            case("queuedShots") { queuedShots(main, container) }
            case("queuedAcrossReload") { queuedAcrossReload(main, container) }
            case("unlockPopups") { unlockPopups(main, container, graphics) }
            case("neutralShips") { neutralShips(main, container) }
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "weapons17 ok: " + results.joinToString(", ")
    }

    private fun fixture(main: MainGame, container: GameContainer): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        state.player.weapons!!.energyLevels = 8
        state.player.purchasedReactorPower = 25
        state.player.updateAvailablePower()
        return state
    }

    private fun give(state: InGameState, slot: Int, name: String): AbstractWeaponInstance {
        val blueprint = state.blueprintManager[name] as AbstractWeaponBlueprint
        val weapon = blueprint.buildInstance(state.player)
        state.player.hardpoints[slot].weapon = weapon
        state.player.cargoUpdated()
        return weapon
    }

    private fun step(main: MainGame, container: GameContainer, seconds: Float) {
        var left = seconds
        while (left > 0f) {
            main.update(container, 0.05f)
            left -= 0.05f
        }
    }

    private fun weaponsRoom(state: InGameState) = state.player.weapons!!.room!!

    private fun ionWeapons(state: InGameState, ion: Int) {
        val damage = Damage()
        damage.ionDamage = ion
        state.player.damage(weaponsRoom(state), damage)
    }

    /** The hotkey/tap path: the player turning an unpowered weapon on. */
    private fun powerOn(state: InGameState, slot: Int): Boolean {
        state.shipUI.weaponHotkeyPressed(slot, false)
        return state.player.hardpoints[slot].weapon!!.isPowered
    }

    private fun spare(state: InGameState) = "reactor spare ${state.player.powerAvailable}, weapons ${state.player.weapons!!.powerSelected}/" +
        "${state.player.weapons!!.undamagedEnergy}, ion ${state.player.weapons!!.ionTimer}, limit ${state.player.weapons!!.ionPowerLimit}"

    /**
     * WeaponControl::OnLoop (1.6.14 0x56c1a0) disarms the armed weapon once its box is not powered; SelectArmament
     * (0x569290) on an unpowered box disarms and powers it. A touch tap on a weapon that lost its power while armed
     * used to reach the armed weapon's autofire / disarm steps (touchSlotClick) and never power it.
     */
    private fun armedDepowered(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container)
        val ui = state.shipUI
        val laser = give(state, 0, "LASER_BURST_2")
        check(powerOn(state, 0)) { "The laser did not power: ${spare(state)}" }
        ui.touchSlotClick(state.player.weapons!!, 0)
        check(ui.touchArmedSlot == 0) { "The tap did not arm the laser" }

        // Ion takes the laser's power while it is armed.
        ionWeapons(state, 2)
        step(main, container, 0.1f)
        check(!laser.isPowered) { "Two ion did not take the laser's power" }
        check(ui.touchArmedSlot == null && state.clickEvent == null) { "The unpowered laser stayed armed" }
        step(main, container, 11f)
        check(!state.player.weapons!!.isIonised) { "The ion did not run out" }
        ui.touchSlotClick(state.player.weapons!!, 0)
        check(laser.isPowered) { "A tap after the ion did not power the laser: ${spare(state)}" }
        ui.touchSlotClick(state.player.weapons!!, 0)
        check(ui.touchArmedSlot == 0) { "The next tap did not arm it" }

        // Armed, then the system is damaged: the same.
        state.player.weapons!!.addDamage(8)
        step(main, container, 0.1f)
        check(!laser.isPowered && ui.touchArmedSlot == null) { "Damage left the laser armed (powered ${laser.isPowered})" }

        // Another armed weapon is disarmed by powering an unpowered one (SelectArmament).
        val fresh = fixture(main, container)
        val first = give(fresh, 0, "LASER_BURST_2")
        val second = give(fresh, 1, "LASER_BURST_1")
        check(powerOn(fresh, 0)) { "Fixture laser did not power" }
        fresh.shipUI.weaponHotkeyPressed(0, false)
        check(fresh.shipUI.touchArmedSlot == 0) { "The laser did not arm" }
        check(powerOn(fresh, 1) && first.isPowered) { "The second laser did not power" }
        check(fresh.shipUI.touchArmedSlot == null && fresh.clickEvent == null) { "Powering a weapon left another armed" }
        check(second.isPowered) { "BrowserGameplayWeapons17:second" }
        return "armed weapon disarmed on power loss, tap powers it"
    }

    /** Ion on the weapons while a weapon is powered or not: once it runs out the player can power the weapon again. */
    private fun powerAfterIon(main: MainGame, container: GameContainer): String {
        for (powered in listOf(true, false)) {
            val state = fixture(main, container)
            val laser = give(state, 0, "LASER_BURST_2")
            give(state, 1, "MISSILES_1")
            if (powered) check(powerOn(state, 0)) { "Fixture laser did not power" }
            ionWeapons(state, 2)
            step(main, container, 0.2f)
            check(!powerOn(state, 0) || laser.isPowered) { "BrowserGameplayWeapons17:ion" }
            step(main, container, 11f)
            check(!state.player.weapons!!.isIonised) { "The ion did not run out" }
            if (laser.isPowered) state.shipUI.weaponHotkeyPressed(0, true)
            check(!laser.isPowered) { "Could not turn the laser off after the ion" }
            check(powerOn(state, 0)) { "After the ion (laser ${if (powered) "on" else "off"} when hit) it would not power: ${spare(state)}" }
            check(powerOn(state, 1)) { "After the ion the missile would not power: ${spare(state)}" }
        }
        return "ion while powered or not, then power"
    }

    /** The weapons destroyed and repaired (by crew), then powered. */
    private fun powerAfterRepair(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container)
        val weapons = state.player.weapons!!
        val laser = give(state, 0, "LASER_BURST_2")
        check(powerOn(state, 0)) { "Fixture laser did not power" }
        val damage = Damage()
        damage.pureSysDamage = 8
        state.player.damage(weaponsRoom(state), damage)
        step(main, container, 0.1f)
        check(!laser.isPowered && weapons.undamagedEnergy == 0) { "The weapons were not destroyed: ${spare(state)}" }
        var guard = 400
        while (weapons.damagedEnergyLevels > 0 && guard-- > 0) weapons.repair(0.25f)
        step(main, container, 0.1f)
        check(powerOn(state, 0)) { "After the repair the laser would not power: ${spare(state)}" }
        return "destroyed, repaired, powered"
    }

    /** An ion storm halves the reactor; the weapons power within what is left, and fully once the storm is gone. */
    private fun powerInStorm(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container)
        val laser = give(state, 0, "LASER_BURST_2")
        val beacon = state.currentBeacon
        beacon.debugSetEnvironment(xyz.znix.xftl.sector.Beacon.EnvironmentType.ION_STORM.create(state, beacon))
        step(main, container, 1f)
        val inStorm = powerOn(state, 0)
        check(inStorm || state.player.powerAvailable < laser.type.power) { "In the storm the laser would not power: ${spare(state)}" }
        if (laser.isPowered) state.shipUI.weaponHotkeyPressed(0, true)
        beacon.clearEnvironment()
        step(main, container, 1f)
        check(powerOn(state, 0)) { "After the storm the laser would not power: ${spare(state)}" }
        return "storm (in storm ${if (inStorm) "powered" else "no room"}), after"
    }

    /**
     * A shot queued while the weapon charges fires once it has a charge: ProjectileFactory::FireNextShot (1.6.14
     * 0x40cfa0) needs chargeLevel >= 1, the weapon powered, its missiles, fireWhenReady or autoFiring, and a target.
     */
    private fun queuedShots(main: MainGame, container: GameContainer): String {
        val done = ArrayList<String>()
        for (name in listOf("LASER_BURST_2", "MISSILES_1", "LASER_CHARGEGUN_PLAYER", "BEAM_1")) {
            for (touch in listOf(false, true)) {
                val state = fixture(main, container)
                val ui = state.shipUI
                val weapons = state.player.weapons!!
                val weapon = give(state, 0, name)
                check(powerOn(state, 0)) { "$name did not power: ${spare(state)}" }
                weapon.timeCharged = 0f
                if (touch) ui.touchSlotClick(weapons, 0) else ui.weaponHotkeyPressed(0, false)
                check(ui.touchArmedSlot == 0) { "$name did not arm" }
                val enemy = state.enemy!!
                val room = enemy.rooms.first()
                if (weapon is xyz.znix.xftl.weapons.BeamBlueprint.BeamInstance) {
                    // A beam takes its line, then the press that ends it.
                    weapons.selectedTargets.targetBeam(0, xyz.znix.xftl.systems.SelectedTarget.BeamAim(weapon, 0, enemy,
                        xyz.znix.xftl.math.Point(enemy.offset.x * 35 + 40, enemy.offset.y * 35 + 40)).also { it.angle = 0.5f; it.updateHitRooms() })
                    state.clickEvent = null
                    ui.onJump()
                } else if (touch) ui.touchClickRoom(room) else {
                    val listener = state.clickEvent ?: error("$name: no click event")
                    state.clickEvent = null
                    listener.roomClicked(room, container)
                }
                check(weapons.selectedTargets.getTarget(0) != null) { "$name (touch $touch): the aim was not queued" }
                var fired = false
                for (i in 0 until 1200) {
                    main.update(container, 0.05f)
                    if (weapon.isFiring || state.player.projectiles.isNotEmpty()) { fired = true; break }
                }
                check(fired) {
                    "$name (touch $touch) never fired: charge ${weapon.timeCharged}/${weapon.chargeTime}, extra ${weapon.extraCharges}, " +
                        "target ${weapons.selectedTargets.getTarget(0) != null}, powered ${weapon.isPowered}"
                }
                done += name + if (touch) " (touch)" else ""
            }
        }
        return "queued shots fire: " + done.joinToString("/")
    }

    /** A queued shot (and an autofire aim) survives a save and load and fires after it. */
    private fun queuedAcrossReload(main: MainGame, container: GameContainer): String {
        var state = fixture(main, container)
        val weapon = give(state, 0, "LASER_CHARGEGUN_PLAYER")
        check(powerOn(state, 0)) { "Fixture charge laser did not power" }
        weapon.timeCharged = 0f
        state.player.weapons!!.selectedTargets.targetRoom(0, state.enemy!!.rooms.first())
        step(main, container, 0.5f)
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        check(state.player.weapons!!.selectedTargets.getTarget(0) != null) { "The queued aim was lost on load" }
        val restored = state.player.hardpoints[0].weapon!!
        var fired = false
        for (i in 0 until 1200) {
            main.update(container, 0.05f)
            if (restored.isFiring || state.player.projectiles.isNotEmpty()) { fired = true; break }
        }
        check(fired) { "After a reload the queued charge laser never fired: ${restored.timeCharged}/${restored.chargeTime} extra ${restored.extraCharges}" }
        return "queued shot across a reload"
    }

    /**
     * Ship unlock popups. The original unlocks by ship type index (ScoreKeeper::UnlockShip 0x448410) and builds a popup
     * for every layout of its ten types whatever the mode, so a vanilla game shows the Lanius's; a name that is no ship
     * gets nothing. Hyperspace builds a custom ship's popup from its layout's achievement image
     * (CustomShipUnlocks::CreateUnlockAchievements), which a mod need not ship (Multiverse's ELITE_SHIP_GHOST:
     * achievements/mupe_dynasty_1.png). None may stop the game.
     */
    private fun unlockPopups(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val profile = main.profile
        val saved = profile.snapshot()
        val wasAe = main.gameContent.enableAdvancedEdition
        try {
            val notices = BrowserProgressNotifications(main)
            fun show(n: SaveProfile.ProgressNotification): Boolean {
                profile.clearNotifications()
                profile.addHsNotification(n)
                notices.update(5f)
                val shown = notices.active
                notices.render(graphics)
                notices.update(5f)
                return shown
            }
            val done = ArrayList<String>()
            profile.achievementPopups = true
            check(!show(SaveProfile.ProgressNotification(ship = "PLAYER_SHIP_NOT_A_SHIP_2"))) { "A name that is no ship got a popup" }
            if (!xyz.znix.xftl.hs.HS.active) {
                main.changeHangarMode(false, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
                check(!main.gameContent.enableAdvancedEdition) { "The game did not switch to vanilla" }
                for (ship in listOf("PLAYER_SHIP_ANAEROBIC", "PLAYER_SHIP_ANAEROBIC_2", "PLAYER_SHIP_HARD_3", "PLAYER_SHIP_CRYSTAL_2"))
                    check(show(SaveProfile.ProgressNotification(ship = ship))) { "No popup for $ship in a vanilla game" }
                done += "vanilla game: Lanius A/B, Kestrel C, Crystal B"
                main.changeHangarMode(true, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            } else if (xyz.znix.xftl.hs.ship.HsShipUnlocks.customShipHasUnlock("ELITE_SHIP_GHOST")) {
                check(show(SaveProfile.ProgressNotification(ship = "ELITE_SHIP_GHOST"))) { "No popup for ELITE_SHIP_GHOST" }
                done += "ELITE_SHIP_GHOST without its image"
            }
            return "unlock popups: " + done.joinToString(", ").ifEmpty { "unknown ship" }
        } finally {
            if (main.gameContent.enableAdvancedEdition != wasAe) main.changeHangarMode(wasAe, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            profile.restore(saved)
            profile.clearNotifications()
        }
    }

    /**
     * Events whose ship comes neutral (`<ship load hostile="false">`): the ship is there and still neutral after a
     * save and load, with the box open and after it (the original's save keeps the ship's hostile flag: native-capture
     * st-van-c1, a neutral Pirate Interceptor).
     */
    private fun neutralShips(main: MainGame, container: GameContainer): String {
        val probe = BrowserGameplayParityDiagnostics.fresh(main, container)
        val names = probe.eventManager.eventNames.sorted().filter { name ->
            val event = runCatching { probe.eventManager[name] as? Event }.getOrNull() ?: return@filter false
            event.loadShipHostile == false && event.loadShipName != null && probe.eventManager.hasShip(event.loadShipName!!)
        }.let { if (BrowserGameplayParityDiagnostics.modded) it.take(40) else it }
        check(names.isNotEmpty()) { "No neutral ship events" }
        val problems = ArrayList<String>()
        for (name in names) {
            var state = BrowserGameplayParityDiagnostics.fresh(main, container)
            state.shipUI.showEventDialogue(state.eventManager[name] as Event, 4242)
            val enemy = state.enemy ?: continue
            fun describe(s: InGameState) = "enemy ${s.enemy?.type?.name}, hostile ${s.isEnemyHostile}, beacon ship ${s.currentBeacon.ship != null}"
            if (state.isEnemyHostile) { problems += "$name: hostile at once"; continue }
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            if (state.enemy == null || state.isEnemyHostile) problems += "$name (box open): ${describe(state)}"
            var closes = 0
            while (closes++ < 8) (state.shipUI.diagnosticDialogue ?: break).selectOption(0)
            step(main, container, 0.5f)
            val before = describe(state)
            val hostileBefore = state.isEnemyHostile
            val presentBefore = state.enemy != null
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            if (state.isEnemyHostile != hostileBefore || (state.enemy != null) != presentBefore)
                problems += "$name (${enemy.type.name}): before $before, after ${describe(state)}"
        }

        // A pirate that surrenders (PIRATE_SURRENDER's first choice: <ship hostile="false"/>), then its crew killed.
        if (!BrowserGameplayParityDiagnostics.modded) {
            var state = fixture(main, container)
            state.shipUI.showEventDialogue(state.eventManager["PIRATE_SURRENDER"] as Event, 77)
            state.shipUI.diagnosticDialogue!!.selectOption(0)
            check(!state.isEnemyHostile && state.currentBeacon.ship == null) { "The surrender did not end the fight" }
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            if (state.enemy == null || state.isEnemyHostile) problems += "surrendered pirate (reward box open): enemy ${state.enemy != null}, hostile ${state.isEnemyHostile}"
            var closes = 0
            while (closes++ < 8) (state.shipUI.diagnosticDialogue ?: break).selectOption(0)
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            if (state.enemy == null || state.isEnemyHostile) problems += "surrendered pirate: enemy ${state.enemy != null}, hostile ${state.isEnemyHostile}"
            for (crew in state.enemy!!.crew.toList()) crew.health = 0f
            step(main, container, 3f)
            closes = 0
            while (closes++ < 8) (state.shipUI.diagnosticDialogue ?: break).selectOption(0)
            check(state.enemy!!.crew.none { it.health > 0f }) { "The pirate crew did not die" }
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            if (state.enemy == null || state.isEnemyHostile) problems += "crew-killed pirate: enemy ${state.enemy != null}, hostile ${state.isEnemyHostile}"
        }
        check(problems.isEmpty()) { problems.joinToString("; ") }
        return "neutral ships across a reload: ${names.size} events" + if (BrowserGameplayParityDiagnostics.modded) "" else ", a surrender, a crew kill"
    }
}
