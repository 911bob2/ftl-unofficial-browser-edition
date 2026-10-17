package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import kotlin.math.abs

/**
 * Round 6 weapon fixes: where a projectile leaves the ship follows the slot the weapon is in now (it was fixed for
 * good the first time the weapon updated, so a dragged weapon kept firing from its old mount), missiles start 16
 * pixels along the firing direction (ProjectileFactory::Update 0x40dc80), and the Ctrl override of autofire
 * (WeaponControl 0x56bd40 / 0x56c1a0 / 0x56b530, WeaponBox::StatusColor 0x506a80).
 *
 * The Kestrel numbers below were read off the original running the same loadout: its Burst Laser Mk II shots
 * flew along screen y 249.6 (slot 3) and 407.6 (slot 2), the Artemis along 283, a constant 169.6 below the
 * ship-space y of 80, 238 and 114 here (kestral.xml mounts plus the ship's hull and room offset of -59,-33).
 */
internal object GameplayParityWeapons6Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val spawn = spawnPoints(main, container)
            val swap = spawnFollowsSlot(main, container)
            val launch = launchedFromCurrentSlot(main, container)
            val override = autofireOverride(main, container)
            val beam = beamOverride(main, container)
            return "weapons6 ok: $spawn, $swap, $launch, $override, $beam"
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun fixture(main: MainGame, container: GameContainer): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        val weapons = state.player.weapons!!
        weapons.energyLevels = 8
        state.player.purchasedReactorPower = 25
        check((state.player.weaponSlots ?: 0) >= 4) { "Fixture ship has ${state.player.weaponSlots} weapon slots" }
        return state
    }

    private fun give(state: InGameState, slot: Int, name: String): AbstractProjectileWeaponInstance {
        val blueprint = state.blueprintManager[name] as AbstractWeaponBlueprint
        val weapon = blueprint.buildInstance(state.player) as AbstractProjectileWeaponInstance
        state.player.hardpoints[slot].weapon = weapon
        state.player.cargoUpdated()
        return weapon
    }

    private fun point(x: Int, y: Int) = ConstPoint(x, y)

    private fun sameSpawn(actual: xyz.znix.xftl.math.IPoint, expected: ConstPoint, what: String) {
        check(actual.x == expected.x && actual.y == expected.y) { "$what leaves from ${actual.x},${actual.y}, not ${expected.x},${expected.y}" }
    }

    /**
     * Ship-space fire positions of the Kestrel's four mounts: hardpoint position plus the fire point less the mount
     * point, mirrored and rotated like the weapon sprite is drawn.
     */
    private fun spawnPoints(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container)
        val burst = (0 until 4).map { give(state, it, "LASER_BURST_3") }
        val expected = listOf(point(515, 113), point(515, 238), point(365, 80), point(365, 270))
        for (slot in 0 until 4) sameSpawn(burst[slot].spawnPosition(0), expected[slot], "Burst Laser Mk II in slot ${slot + 1}")

        val artemis = give(state, 0, "MISSILES_1")
        sameSpawn(artemis.spawnPosition(0), point(491, 114), "Artemis in slot 1")
        val artemisLow = give(state, 1, "MISSILES_1")
        sameSpawn(artemisLow.spawnPosition(0), point(491, 237), "Artemis in slot 2")
        return "fire points of 4 mounts"
    }

    /** The cached position of the old code followed the weapon's first slot for ever. */
    private fun spawnFollowsSlot(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container)
        val weapons = state.player.weapons!!
        val burst = give(state, 1, "LASER_BURST_3")
        val missile = give(state, 0, "MISSILES_1")
        sameSpawn(burst.spawnPosition(0), point(515, 238), "Burst before the swap")
        sameSpawn(missile.spawnPosition(0), point(491, 114), "Missile before the swap")

        check(weapons.swapSlots(0, 1)) { "Swap refused" }
        sameSpawn(burst.spawnPosition(0), point(515, 113), "Burst after moving to slot 1")
        sameSpawn(missile.spawnPosition(0), point(491, 237), "Missile after moving to slot 2")

        // Into an empty slot as well, and back again.
        check(weapons.swapSlots(0, 3)) { "Move to slot 4 refused" }
        sameSpawn(burst.spawnPosition(0), point(365, 270), "Burst after moving to slot 4")
        check(weapons.swapSlots(3, 2)) { "Move to slot 3 refused" }
        sameSpawn(burst.spawnPosition(0), point(365, 80), "Burst after moving to slot 3")

        // A weapon dragged out of the ship and a new one dropped in (ShipEquipmentPanel) is a new instance.
        val fresh = give(state, 2, "LASER_BURST_3")
        sameSpawn(fresh.spawnPosition(0), point(365, 80), "Replacement in slot 3")
        return "spawn follows swaps"
    }

    /** The projectile itself, not just the helper, must start at the mount of the slot the weapon is in. */
    private fun launchedFromCurrentSlot(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container)
        val player = state.player
        val weapons = player.weapons!!
        val burst = give(state, 0, "LASER_BURST_3")
        val missile = give(state, 1, "MISSILES_1")
        check(weapons.setWeaponPower(burst, true) && weapons.setWeaponPower(missile, true)) { "Fixture weapons did not power" }

        // Swap them: the burst laser now sits in the mount that is lower and further forward.
        check(weapons.swapSlots(0, 1)) { "Swap refused" }
        check(weapons.swapSlots(1, 3)) { "Move refused" }
        burst.timeCharged = burst.chargeTime
        missile.timeCharged = missile.chargeTime
        val enemy = state.enemy!!
        weapons.selectedTargets.targetRoom(3, enemy.rooms.first())
        weapons.selectedTargets.targetRoom(0, enemy.rooms.last())

        var shot: xyz.znix.xftl.weapons.IProjectile? = null
        var missileShot: xyz.znix.xftl.weapons.IProjectile? = null
        // Small steps, so the first position seen is within a few pixels of where the shot was made.
        for (i in 0 until 3000) {
            main.update(container, 0.004f)
            if (shot == null) shot = player.projectiles.firstOrNull { it.position.y > 250f }
            if (missileShot == null) missileShot = player.projectiles.firstOrNull { it.position.y < 250f }
            if (shot != null && missileShot != null) break
        }
        check(shot != null && missileShot != null) { "The weapons never fired: ${player.projectiles.size} projectiles" }

        // Burst laser in slot 4: ship-space (365,270); the missile in slot 1: (491,114) plus the 16 pixel advance.
        // The game steps in fixed ticks of up to 1/30 s, so the shot has flown at most that far when first seen. The
        // missile's lower bound is what shows the 16 pixel advance: without it the first sighting would be below 507.
        fun reach(projectile: xyz.znix.xftl.weapons.IProjectile): Float =
            ((projectile as? xyz.znix.xftl.weapons.AbstractProjectile)?.speed ?: 0) / 30f + 1f
        check(abs(shot.position.y - 270f) < 0.01f && shot.position.x >= 365f && shot.position.x < 365f + reach(shot)) {
            "Burst laser shot started at ${shot.position.x},${shot.position.y}"
        }
        check(abs(missileShot.position.y - 114f) < 0.01f && missileShot.position.x >= 507f && missileShot.position.x < 507f + reach(missileShot)) {
            "Missile started at ${missileShot.position.x},${missileShot.position.y}"
        }
        return "shots leave from the current mount, missile 16 px ahead"
    }

    private fun select(state: InGameState, slot: Int, ctrlAtSelection: Boolean = false) {
        state.shipUI.weaponHotkeyPressed(slot, false, ctrlAtSelection)
        check(state.clickEvent != null) { "Selecting slot ${slot + 1} did not arm the weapon" }
    }

    private fun clickRoom(state: InGameState, container: GameContainer, room: xyz.znix.xftl.layout.Room) {
        val listener = state.clickEvent ?: error("No weapon is armed")
        state.clickEvent = null
        listener.roomClicked(room, container)
    }

    private fun autofireOverride(main: MainGame, container: GameContainer): String {
        var state = fixture(main, container)
        var weapons = state.player.weapons!!
        val laser = give(state, 0, "LASER_BURST_3")
        check(weapons.setWeaponPower(laser, true)) { "Fixture weapon did not power" }
        val room = state.enemy!!.rooms.first()
        fun target() = weapons.selectedTargets.getTarget(0)!!

        // Autofire off: a plain click aims normally, a Ctrl click makes the weapon autofire at that spot.
        select(state, 0)
        check(!state.shipUI.isAimingAutofire()) { "Plain aim previewed autofire" }
        clickRoom(state, container, room)
        check(laser.autofireOverride == null && !target().isAutofire) { "Plain click: ${laser.autofireOverride}" }
        weapons.selectedTargets.unTarget(0)

        select(state, 0)
        BrowserEngine.key(Input.KEY_LCTRL, 0, true)
        check(state.shipUI.isAimingAutofire()) { "Ctrl did not preview autofire" }
        clickRoom(state, container, room)
        BrowserEngine.key(Input.KEY_LCTRL, 0, false)
        check(laser.autofireOverride == true && target().isAutofire) { "Ctrl click: ${laser.autofireOverride}" }

        // The target stays and fires again, like one chosen with the autofire button on.
        laser.timeCharged = laser.chargeTime
        weapons.selectedTargets.fireChargedWeapons()
        check(laser.isFiring && weapons.selectedTargets.getTarget(0) != null) { "Override target was dropped after firing" }
        settle(main, container, laser)

        // It survives a save and load, and an old save without the element loads as no override.
        val saved = BrowserXml.write(state.saveGameState())
        check(saved.contains("<autofireOverride slot=\"0\">true</autofireOverride>")) { "Override was not saved" }
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        weapons = state.player.weapons!!
        val restored = state.player.hardpoints[0].weapon!!
        check(restored.autofireOverride == true && weapons.selectedTargets.getTarget(0)!!.isAutofire) { "Override lost on load" }
        val old = BrowserXml.parse(saved.replace("<autofireOverride slot=\"0\">true</autofireOverride>", ""))
        main.loadSavedGame(old)
        state = main.currentState as InGameState
        weapons = state.player.weapons!!
        check(state.player.hardpoints[0].weapon!!.autofireOverride == null && !weapons.selectedTargets.getTarget(0)!!.isAutofire) {
            "A save without the element did not load as normal fire"
        }

        // Rendering the yellow crosshairs and the armed boxes must not need anything missing.
        for (n in 1..4) state.getImg("img/misc/crosshairs_placed${n}_yellow.png")
        state.getImg("img/misc/beam_center_yellow.png")
        weapons.selectedTargets.targetRoom(0, state.enemy!!.rooms.first())
        BrowserEngine.renderFrame(1f / 60f)

        // Autofire on: a plain click autofires, a Ctrl click fires once and the target clears.
        weapons.setAutofire(true)
        val second = state.player.hardpoints[0].weapon!!
        settle(main, container, second)
        weapons.selectedTargets.unTarget(0)
        check(weapons.setWeaponPower(second, true) || second.isPowered) { "Fixture weapon lost power" }
        val enemyRoom = state.enemy!!.rooms.first()
        select(state, 0)
        check(state.shipUI.isAimingAutofire()) { "Autofire mode did not preview autofire" }
        clickRoom(state, container, enemyRoom)
        check(second.autofireOverride == null && weapons.selectedTargets.getTarget(0)!!.isAutofire) { "Plain click in autofire mode" }
        weapons.selectedTargets.unTarget(0)

        select(state, 0)
        BrowserEngine.key(Input.KEY_LCTRL, 0, true)
        check(!state.shipUI.isAimingAutofire()) { "Ctrl in autofire mode previewed autofire" }
        clickRoom(state, container, enemyRoom)
        BrowserEngine.key(Input.KEY_LCTRL, 0, false)
        check(second.autofireOverride == false && !weapons.selectedTargets.getTarget(0)!!.isAutofire) { "Ctrl click in autofire mode: ${second.autofireOverride}" }
        second.timeCharged = second.chargeTime
        weapons.selectedTargets.fireChargedWeapons()
        check(second.isFiring && weapons.selectedTargets.getTarget(0) == null) { "Ctrl click in autofire mode did not fire just once" }
        settle(main, container, second)

        // Ctrl held when the weapon was chosen (the keyboard hotkeys pass it) counts even if released before the click.
        weapons.setAutofire(false)
        select(state, 0, ctrlAtSelection = true)
        clickRoom(state, container, enemyRoom)
        check(second.autofireOverride == true && weapons.selectedTargets.getTarget(0)!!.isAutofire) { "Ctrl held at selection was ignored" }

        // Targets are dropped with the fight (a jump here), and the next aim decides the mode again.
        weapons.onJump()
        check(weapons.selectedTargets.getTarget(0) == null) { "Target survived the jump" }
        state.shipUI.onJump()
        select(state, 0)
        clickRoom(state, container, state.enemy?.rooms?.first() ?: state.player.rooms.first())
        check(second.autofireOverride == null) { "A new aim kept the earlier override" }
        return "override on/off, keyboard select, save/load, old save, jump"
    }

    /** A beam is aimed with a drag and aimed for good by the click that ends it; Ctrl is read at that click. */
    private fun beamOverride(main: MainGame, container: GameContainer): String {
        val state = fixture(main, container)
        val weapons = state.player.weapons!!
        val beam = (state.blueprintManager["BEAM_1"] as AbstractWeaponBlueprint).buildInstance(state.player)
        state.player.hardpoints[0].weapon = beam
        state.player.cargoUpdated()
        check(weapons.setWeaponPower(beam, true)) { "Fixture beam did not power" }
        val room = state.enemy!!.rooms.first()

        fun aimBeam(ctrlAtRelease: Boolean, previewsAutofire: Boolean) {
            select(state, 0)
            clickRoom(state, container, room)
            if (ctrlAtRelease) BrowserEngine.key(Input.KEY_LCTRL, 0, true)
            check(state.shipUI.isAimingAutofire() == previewsAutofire) { "Beam aim preview with Ctrl=$ctrlAtRelease: ${state.shipUI.isAimingAutofire()}" }
            state.shipUI.mouseClick(Input.MOUSE_LEFT_BUTTON, 0, 0, ConstPoint.ZERO)
            BrowserEngine.key(Input.KEY_LCTRL, 0, false)
        }

        aimBeam(false, false)
        check(beam.autofireOverride == null && !weapons.selectedTargets.getTarget(0)!!.isAutofire) { "Plain beam: ${beam.autofireOverride}" }
        weapons.selectedTargets.unTarget(0)

        aimBeam(true, true)
        check(beam.autofireOverride == true && weapons.selectedTargets.getTarget(0)!!.isAutofire) { "Ctrl beam: ${beam.autofireOverride}" }
        // The yellow beam is drawn for it, and the placed beam survives a frame.
        BrowserEngine.renderFrame(1f / 60f)
        weapons.selectedTargets.unTarget(0)

        weapons.setAutofire(true)
        aimBeam(true, false)
        check(beam.autofireOverride == false && !weapons.selectedTargets.getTarget(0)!!.isAutofire) { "Ctrl beam in autofire mode: ${beam.autofireOverride}" }
        return "beam override"
    }

    /** Let a volley finish: the next aim needs an idle weapon, and a firing one is skipped by the target list. */
    private fun settle(main: MainGame, container: GameContainer, weapon: AbstractWeaponInstance) {
        for (i in 0 until 400) {
            if (!weapon.isFiring) return
            main.update(container, 0.05f)
        }
        error("The weapon never finished firing")
    }
}
