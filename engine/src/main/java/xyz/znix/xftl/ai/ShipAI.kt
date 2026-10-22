package xyz.znix.xftl.ai

import ftl.browser.BrowserEnemyTargeting
import ftl.browser.BrowserRandom
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.Shields
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The overall enemy ship AI.
 *
 * This is the port of ShipAI::OnLoop (0x625c50) and the CombatAI it owns: power management, weapon aiming and
 * firing, cloaking, hacking, mind control, and the surrender / escape / stalemate decisions of WorldManager::OnLoop.
 *
 * It specifically does not handle crew - see [FriendlyCrewAI] for that.
 */
class ShipAI @JvmOverloads constructor(val ship: Ship, val player: Ship, private val rand: Random = BrowserRandom) {
    /** CombatAI's per-weapon charge goal (ProjectileFactory +0x634): fire once this many charges are ready. */
    private val chargeGoal = HashMap<AbstractWeaponInstance, Int>()

    /** CombatAI +0x2c: keep aiming new shots while cloaked. Re-rolled every time the cloak starts. */
    var fireWhileCloaked = false
        private set

    /** ShipAI +0xec: the stalemate flag of the previous frame. */
    private var stalemateFlag = ship.aiStalemateTimer > 0f

    /** ShipAI +0xf4: the hull at the end of the previous update. */
    private var lastHull = ship.health

    /** Escaping, ShipAI +0xaa: the escape timer is running. */
    val isEscaping: Boolean get() = ship.escapeTimer != null

    /**
     * The flagship's stalemate exit: ShipAI::OnLoop (0x625c50) calls ShipManager::JumpLeave directly once the stalemate
     * timer reaches two minutes, with no check that its FTL could charge. While this is set the escape timer stays at
     * zero and the "FTL can't charge" hold-back is skipped.
     */
    private var stalemateLeave = false

    fun update(dt: Float) {
        // ShipAI::OnLoop (0x625c50): a ship with no hull left is treated like a non-hostile one.
        if (ship.isDead) {
            updateIdle()
            return
        }

        // WorldManager::OnLoop (0x61b930): SetStalemate, then the ship's own OnLoop (stalemate block, power, combat,
        // escape countdown), then the surrender / stalemate / escape checks, which wait while the player is jumping.
        setStalemate(computeStalemate())

        updateStalemate(dt)

        ship.backupBattery?.startBattery()

        updatePower()
        updateCombat()

        updateEscapeTimer(dt)

        lastHull = ship.health

        if (!player.browserJumping)
            checkSurrenderAndEscape()
    }

    /**
     * The non-hostile / dying half of ShipAI::OnLoop (0x625c50): nothing is powered for the fight any more.
     * CombatAI::DeactiveWeapons (0x62b080) turns every weapon and drone off, hacking and mind control stop.
     */
    fun updateIdle() {
        // TeleportSystem::ForceReady (0x648390): the teleporter is charged and unlocked at once.
        ship.teleporter?.lockUnits(0)

        stalemateLeave = false

        val weapons = ship.weapons
        if (weapons != null) {
            for (slot in ship.hardpoints.indices) {
                if (ship.hardpoints[slot].weapon?.isPowered == true)
                    weapons.powerManager.setItemPower(slot, false)
            }
        }
        val drones = ship.drones
        if (drones != null) {
            for (slot in drones.drones.indices) {
                drones.setDronePower(slot, false)
            }
        }
        ship.hacking?.stopHacking()

        // FUN_0049cb30 -> Shields::DrainSuperShield (0x631770): a ship that isn't fighting loses its super shield.
        if (ship.shields != null)
            ship.superShield = 0

        ship.mindControl?.stopMindControl()

        // ShipAI +0xd8 and CrewAI +0x5c (FUN_00626360(0)): nobody is being sent to the other ship.
        ship.crewAI.resetTeleportBudget()

        chargeGoal.clear()
    }

    // ---- Surrender, escape and stalemate (WorldManager::OnLoop 0x61b930 and ShipAI 0x6251f0-0x625720) ----

    /**
     * The stalemate rule of WorldManager::OnLoop (0x61bc30): it needs the player to be unable to jump away - the
     * engines' or piloting's power cap is zero (ShipSystem::GetPowerCap 0x63b790: the system level, or an event's
     * power limit; damage and ion don't count) or there is no fuel - and nobody boarding this ship.
     */
    private fun computeStalemate(): Boolean {
        val engines = player.engines
        val piloting = player.piloting
        val canJump = engines != null && nativePowerCap(engines) != 0
                && piloting != null && nativePowerCap(piloting) != 0
                && player.fuelCount > 0
        if (canJump)
            return false
        return ship.intruders.none { it is LivingCrew && it.currentAction != AbstractCrew.Action.DYING }
    }

    /** ShipAI::SetStalemate (0x6251f0): the timer restarts unless the stalemate continues. */
    private fun setStalemate(flag: Boolean) {
        if (!flag || !stalemateFlag)
            ship.aiStalemateTimer = 0f
        stalemateFlag = flag
    }

    /** ShipAI::GetStalemate (0x625220): a stalemate older than two minutes ends the fight (not for the flagship). */
    private fun stalemateReached(): Boolean {
        if (ship.aiStalemateTimer > STALEMATE_SURRENDER_TIME && stalemateFlag && ship.boss == null) {
            ship.aiStalemateTimer = 0f
            return true
        }
        return false
    }

    /**
     * ShipAI::Surrender (0x6255f0), then GetStalemate, then 0x625620 (the escape check), in WorldManager::OnLoop
     * order: surrendering is checked first and also stops any later escape.
     */
    private fun checkSurrenderAndEscape() {
        val spec = ship.spec
        val health = ship.health

        // WorldManager::OnLoop: a ship that is jumping away still marks itself surrendered, but shows neither event.
        if (health in 1..ship.surrenderHealth && !ship.aiSurrendered) {
            ship.aiSurrendered = true
            val event = spec?.surrender
            if (!ship.browserJumping) {
                if (event != null)
                    ship.sys.showShipEnding(event, ship)
                return
            }
        }

        if (stalemateReached() && !ship.browserJumping) {
            // UpdateLocation1 0x61c885: CreateEvent("STALEMATE_SURRENDER", level 0, seed -1), the global stream.
            val event = ship.sys.eventManager["STALEMATE_SURRENDER"]
            ship.sys.eventCreator.create(event, 0)?.let { ship.sys.shipUI.showEventDialogue(it) }
            return
        }

        if (health in 1..ship.escapeHealth && !isEscaping && !ship.aiSurrendered) {
            val event = spec?.escape
            if (event != null) {
                // ShipAI::SetEscapeTimer (0x6254c0): a timer that isn't positive means 15 seconds.
                ship.escapeTimer = startingEscapeTimer()
                ship.sys.showShipEnding(event, ship)
            } else {
                // 0x625990: a missing escape event cancels the escape for good.
                ship.escapeHealth = -100
            }
        }
    }

    private fun startingEscapeTimer(): Float {
        val timer = ship.spec?.escapeTimer ?: 0f
        return if (timer > 0f) timer else DEFAULT_ESCAPE_TIMER
    }

    /**
     * The stalemate block of ShipAI::OnLoop: the ship starts to escape whatever its hull, the timer runs, and a
     * stalemate older than two minutes makes the flagship jump away.
     */
    private fun updateStalemate(dt: Float) {
        if (!stalemateFlag) {
            stalemateLeave = false
            return
        }

        ship.escapeHealth = STALEMATE_ESCAPE_HEALTH
        ship.aiStalemateTimer += dt

        if (!isEscaping)
            ship.escapeTimer = max(STALEMATE_ESCAPE_TIMER, startingEscapeTimer())

        if (lastHull != ship.health)
            ship.aiStalemateTimer = min(ship.aiStalemateTimer, STALEMATE_TIMER_CAP)

        // The flagship (ShipAI +0xf8) leaves once the timer reaches two minutes and it isn't jumping already.
        stalemateLeave = ship.boss != null && ship.aiStalemateTimer >= STALEMATE_SURRENDER_TIME
        if (stalemateLeave)
            ship.escapeTimer = 0f
    }

    /**
     * The escape countdown of ShipAI::OnLoop: it only runs while the ship could charge its FTL, and the player's
     * FTL Jammer halves its speed (0x46a150 only checks that the augment is present).
     */
    private fun updateEscapeTimer(dt: Float) {
        val timer = ship.escapeTimer ?: return

        // ShipManager::JumpLeave is called directly, so the jump doesn't wait for a charged FTL.
        if (stalemateLeave) {
            ship.escapeTimer = 0f
            return
        }

        if (ship.canChargeFTL) {
            val rate = if (player.hasAugment(AugmentBlueprint.FTL_JAMMER)) JAMMER_RATE else 1f
            ship.escapeTimer = timer - dt * rate
        }

        // The jump itself also needs a working FTL (InGameState starts the fly-out once the timer is not positive),
        // so a ship that lost its engines or pilot at the last moment stays until it recovers.
        val remaining = ship.escapeTimer!!
        if (remaining <= 0f && !ship.canChargeFTL)
            ship.escapeTimer = MIN_PENDING_TIMER
    }

    // ---- Power management (0x6259b0) ----

    // The last step of 0x6259b0 walks ShipManager::GetSystemList (0x49e1d0, a copy of the system vector, filled in
    // blueprint order) and raises every system until it refuses - every artillery copy included, not one per id.
    private val nativeOrder: List<MainSystem>
        get() = ship.mainSystems.sortedBy { it.configuration.spec.systemIndex }

    /**
     * ShipAI power management (0x6259b0). The original takes all the power away every frame and hands it out
     * again in a fixed order; this works the same allocation out first and then only applies the differences, so
     * weapons, drones and the systems aren't toggled needlessly.
     *
     * The order: oxygen, teleporter, clonebay (and the medbay when somebody needs healing) get one bar each, then
     * a loop of {the unpowered weapon with the most charge, a drone, shields, cloaking, engines} until nothing more
     * fits (when escaping only shields, cloaking and engines), and finally what is left goes to every system in
     * turn.
     */
    private fun updatePower() {
        val plan = PowerPlan()

        plan.increase(ship.oxygen)
        plan.increase(ship.teleporter)
        plan.increase(ship.clonebay)
        if (someoneNeedsHealing())
            plan.increase(ship.medbay)

        val hostile = !isEscaping
        while (true) {
            if (hostile) {
                plan.increaseWeapon()
                if (plan.increase(ship.drones)) {
                    plan.increase(ship.shields)
                    plan.increase(ship.cloaking)
                    plan.increase(ship.engines)
                    continue
                }
            }

            // Every one of the three is tried, even when an earlier one worked.
            val shields = plan.increase(ship.shields)
            val cloaking = plan.increase(ship.cloaking)
            val engines = plan.increase(ship.engines)
            if (!shields && !cloaking && !engines)
                break
        }

        ship.aiDestroyedSystems = ship.systems.count { it.broken }

        for (system in nativeOrder) {
            while (plan.increase(system)) {
                // Keep going until the system won't take any more.
            }
        }

        plan.apply()
    }

    /** CrewAI::FUN_006270f0 (+0x7): crew that is hurt enough, or still healing, keeps the medbay powered. */
    private fun someoneNeedsHealing(): Boolean {
        val medbayRoom = ship.medbay?.room
        for (crew in ship.friendlyCrew) {
            if (crew !is LivingCrew)
                continue
            val fraction = crew.health / crew.maxHealth
            if (fraction < NEEDS_HEALING_LOW)
                return true
            if (fraction < NEEDS_HEALING_CALM && ship.crewAI.isCalm)
                return true
            if (crew.room == medbayRoom && fraction < 1f)
                return true
        }
        return false
    }

    /**
     * The power allocation worked out for the frame. [level] is the power each unlocked system ends up with,
     * [weaponOn] and [droneOn] which weapons and drones are powered.
     */
    private inner class PowerPlan {
        private val systems = ship.mainSystems
        private val level = HashMap<MainSystem, Int>()
        private val weaponOn = BooleanArray(ship.hardpoints.size)
        private val droneOn = BooleanArray(ship.drones?.drones?.size ?: 0)

        /** The power nobody holds on to: the unused reactor power plus everything unlocked systems hold now. */
        private var free: Int = ship.powerAvailable

        /** Drone parts still available for deploying new drones. */
        private var parts: Int = ship.dronesCount

        init {
            for (system in systems) {
                if (system.isPowerLocked) {
                    // FUN_0063bc00: a locked system keeps whatever it has.
                    level[system] = system.powerSelected
                    continue
                }
                level[system] = 0
                free += system.powerSelected
            }

            val weapons = ship.weapons
            if (weapons != null && weapons.isPowerLocked) {
                for ((slot, hp) in ship.hardpoints.withIndex())
                    weaponOn[slot] = hp.weapon?.isPowered == true
            }
            val drones = ship.drones
            if (drones != null && drones.isPowerLocked) {
                for ((slot, info) in drones.drones.withIndex())
                    droneOn[slot] = info?.instance?.isPowered == true
            }
        }

        /** ShipManager::IncreaseSystemPower: one more step of power, true if the system took it. */
        fun increase(system: AbstractSystem?): Boolean {
            if (system !is MainSystem)
                return false
            if (system.isPowerLocked)
                return false

            return when (system) {
                is Weapons -> increaseNextWeapon(system)
                is Drones -> increaseNextDrone(system)
                else -> {
                    val step = if (system is Shields) 2 else 1
                    val current = level[system] ?: return false
                    if (step > free || current + step > system.undamagedEnergy)
                        return false
                    level[system] = current + step
                    free -= step
                    true
                }
            }
        }

        /** The weapon pick of the loop: the unpowered weapon with the most charge (the last of equals wins). */
        fun increaseWeapon() {
            val weapons = ship.weapons ?: return
            if (weapons.isPowerLocked)
                return

            var best = -1
            var bestSlot = -1
            for ((slot, hp) in ship.hardpoints.withIndex()) {
                val weapon = hp.weapon ?: continue
                if (weaponOn[slot])
                    continue
                if (best.toFloat() < weapon.timeCharged) {
                    best = weapon.timeCharged.toInt()
                    bestSlot = slot
                }
            }
            if (bestSlot == -1)
                return

            // FUN_00636230: a weapon without missiles can't be powered, and it has to fit.
            val weapon = ship.hardpoints[bestSlot].weapon!!
            val draw = weapon.type.power
            if (!weapon.hasEnoughMissiles || !fits(weapons, draw))
                return
            powerWeapon(weapons, bestSlot, draw)
        }

        /** Does another item drawing [draw] fit in the reactor's spare power and the system's undamaged bars? */
        private fun fits(system: MainSystem, draw: Int): Boolean {
            return draw <= free && (level[system] ?: 0) + draw <= system.undamagedEnergy
        }

        private fun powerWeapon(weapons: Weapons, slot: Int, draw: Int) {
            weaponOn[slot] = true
            level[weapons] = (level[weapons] ?: 0) + draw
            free -= draw
        }

        private fun increaseNextWeapon(weapons: Weapons): Boolean {
            for ((slot, hp) in ship.hardpoints.withIndex()) {
                val weapon = hp.weapon ?: continue
                if (weaponOn[slot] || !weapon.hasEnoughMissiles)
                    continue
                val draw = weapon.type.power
                if (!fits(weapons, draw))
                    continue
                powerWeapon(weapons, slot, draw)
                return true
            }
            return false
        }

        private fun increaseNextDrone(drones: Drones): Boolean {
            for ((slot, info) in drones.drones.withIndex()) {
                if (info == null || droneOn[slot])
                    continue
                if (info.type.type == DroneBlueprint.DroneType.HACKING || info.cooldown != null)
                    continue

                // A drone that isn't out yet needs a part to deploy, and a ship to fight if it attacks.
                val deploying = info.instance == null
                if (deploying && parts <= 0 && !ship.sys.debugFlags.infiniteDrones.set)
                    continue

                val draw = info.type.power
                if (!fits(drones, draw))
                    continue

                droneOn[slot] = true
                level[drones] = (level[drones] ?: 0) + draw
                free -= draw
                if (deploying)
                    parts--
                return true
            }
            return false
        }

        /** Make the ship match the plan: lower what has to go first, so the power is there for the rest. */
        fun apply() {
            val weapons = ship.weapons
            if (weapons != null && !weapons.isPowerLocked) {
                for ((slot, hp) in ship.hardpoints.withIndex()) {
                    if (hp.weapon?.isPowered == true && !weaponOn[slot])
                        weapons.powerManager.setItemPower(slot, false)
                }
            }
            val drones = ship.drones
            if (drones != null && !drones.isPowerLocked) {
                for ((slot, info) in drones.drones.withIndex()) {
                    if (info?.instance?.isPowered == true && !droneOn[slot])
                        drones.setDronePower(slot, false)
                }
            }

            for (system in systems) {
                if (system is Weapons || system is Drones || system.isPowerLocked)
                    continue
                var guard = system.energyLevels
                while (system.powerSelected > (level[system] ?: 0) && guard-- > 0)
                    system.decreasePower()
            }

            if (weapons != null && !weapons.isPowerLocked) {
                for ((slot, hp) in ship.hardpoints.withIndex()) {
                    val weapon = hp.weapon ?: continue
                    if (weaponOn[slot] && !weapon.isPowered)
                        weapons.powerManager.setItemPower(slot, true)
                }
            }
            if (drones != null && !drones.isPowerLocked) {
                for ((slot, info) in drones.drones.withIndex()) {
                    if (info != null && droneOn[slot] && info.instance?.isPowered != true)
                        drones.setDronePower(slot, true)
                }
            }

            for (system in systems) {
                if (system is Weapons || system is Drones || system.isPowerLocked)
                    continue
                var guard = system.energyLevels
                while (system.powerSelected < (level[system] ?: 0) && guard-- > 0)
                    system.increasePower()
            }
        }
    }

    // ---- CombatAI (0x62c510) ----

    private fun updateCombat() {
        val target = player

        // CombatAI::OnLoop: no new shots are aimed while this ship is cloaked, unless the flag allows it.
        if (fireWhileCloaked || !ship.isCloakActive) {
            if (!target.isDead)
                aimWeapons(target)
        }

        // A cloak that can be started always is; whether the ship keeps firing behind it is a coin flip.
        val cloaking = ship.cloaking
        if (cloaking != null && !cloaking.active) {
            cloaking.activateCloak()
            if (cloaking.active)
                fireWhileCloaked = rand.nextBoolean()
        }

        updateHacking(target)
        updateMindControl()
    }

    /**
     * The weapon pre-aim (0x62c000). A powered weapon with a ready charge that has reached its charge goal picks its
     * targets and fires; weapons with several charge levels also go early with a chance that grows with each level.
     */
    private fun aimWeapons(target: Ship) {
        if (ship.sys.debugFlags.noEnemyFire.set)
            return

        for (hp in ship.hardpoints) {
            val weapon = hp.weapon ?: continue
            val ready = weapon.totalReadyCharges
            if (!weapon.isPowered || !weapon.hasEnoughMissiles || ready < 1 || weapon.isFiring)
                continue

            val levels = weapon.maxTotalCharges
            val goal = chargeGoal.getOrPut(weapon) { selectChargeGoal(levels) }
            if (goal > ready)
                continue
            // 0x62c000: a charged weapon aims at nothing while the target ship is cloaked (vtable +0x10), before the roll.
            if (target.isCloakActive)
                continue
            if (levels >= 2 && levels != ready && rand.nextInt(levels - ready + 1) != 0)
                continue

            when (weapon) {
                is IRoomTargetingWeapon -> {
                    // ProjectileFactory::NumTargetsRequired (0x40d5f0): a weapon with several charge levels aims once
                    // per charge, any other one once per shot of its burst (blueprint +0x108, copied to +0x134).
                    // Every point is chosen on its own, so a burst can spread over several rooms.
                    val shots = max(1, weapon.type.shots)
                    val perPoint = if (levels > 1) shots else 1
                    val aimed = chooseAimPoints(target, if (levels > 1) max(1, ready) else shots)

                    // The weapon fires the last target it was given first, while the original fires the first aim
                    // point first (SetTargets 0x40d9a0 reverses the list), so hand the points out back to front.
                    val handOut = handOutAimPoints(aimed, max(1, ready) * shots, perPoint).iterator()
                    weapon.fire { handOut.next() }
                }

                is BeamBlueprint.BeamInstance -> {
                    val aimed = chooseAimPoints(target, 2)
                    val aim = weapon.buildAim(aimed[0], aimed[aimed.size - 1], false)

                    // Set the weapon to start firing
                    weapon.fire(aim)

                    // Play the beam sound effect
                    weapon.type.launchSounds?.get()?.play()
                }

                else -> continue
            }

            // ProjectileFactory::SelectChargeGoal, right after the targets are set.
            chargeGoal[weapon] = selectChargeGoal(levels)
        }
    }

    private fun selectChargeGoal(levels: Int): Int = rand.nextInt(max(1, levels)) + 1

    /**
     * Pick the rooms a weapon aims at. Every point comes from ChooseSystem (or a random room); a point that is
     * already taken replaces the earlier one, so a weapon never aims twice at the same room.
     */
    private fun chooseAimPoints(target: Ship, count: Int): List<Room> {
        val needed = min(count, target.rooms.size)
        val points = ArrayList<Room>()
        var attempts = 0
        while (points.size < needed && attempts++ < MAX_AIM_ATTEMPTS) {
            val room = BrowserEnemyTargeting.pickRoom(ship, target, rand)
            points.remove(room)
            points.add(room)
        }
        return points
    }

    /**
     * The hacking half of CombatAI::OnLoop (0x62c510): while no probe is out, send one (if there's a drone part to
     * spend) at a uniformly chosen system of the target; once it landed, start the pulse (0x62d710) unless the
     * hacked system is gone or the hacking system has no power.
     */
    private fun updateHacking(target: Ship) {
        val hacking = ship.hacking ?: return
        if (target.isDead)
            return

        if (!hacking.droneLaunched) {
            if (ship.dronesCount > 0) {
                val systems = target.systems
                if (systems.isNotEmpty())
                    hacking.selectTarget(systems[rand.nextInt(systems.size)].room!!)
            }
            return
        }

        // CombatAI 0x62d710: the hacking system itself needs power (its effective power, not the target's), the
        // hacked system must still exist, and the probe must have landed. Its own power lock is checked by
        // startHackingPulse; the target's lock doesn't matter.
        val hacked = hacking.droneTargetSystem ?: return
        if (!canStartPulse(hacked.broken, hacking.powerSupplied))
            return
        hacking.startHackingPulse()
    }

    /**
     * CombatAI::UpdateMindControl (0x62ad50): when the system is ready, one crewmember is drawn uniformly from the
     * list CrewMemberFactory 0x467250 builds for ship id 0 - everyone currently aboard the player's ship, its crew
     * and the boarders sent there - without asking whether they resist (that only drops them from the room's queue
     * afterwards, which [MindControl.selectCrew] does). While the system is hacked (level >= 2 with the pulse on)
     * the id is 1 instead, so the candidates are everyone aboard this ship. Dead crew and drone bodies are left out
     * (vtable +0x58).
     */
    private fun updateMindControl() {
        val mindControl = ship.mindControl ?: return
        if (!mindControl.ready)
            return

        val aboard = mindControlPool(mindControl.isHackActive, ship, player)
        val candidates = ArrayList<LivingCrew>()
        for (crew in aboard) {
            if (crew is LivingCrew && crew.currentAction != AbstractCrew.Action.DYING)
                candidates.add(crew)
        }
        if (candidates.isEmpty())
            return

        mindControl.selectCrew(candidates[rand.nextInt(candidates.size)])
    }

    companion object {
        /**
         * The order a weapon is given its [total] targets in: the weapon fires the last one first, so the aim points
         * go out back to front, each repeated for [perPoint] shots (SetTargets 0x40d9a0 reverses the aim list).
         */
        internal fun <T> handOutAimPoints(aimed: List<T>, total: Int, perPoint: Int): List<T> {
            return (0 until total).map { call -> aimed[min(max(0, total - 1 - call) / perPoint, aimed.size - 1)] }
        }

        /** CrewMemberFactory 0x467250 with ship id 0, or 1 while the mind control system is hacked. */
        internal fun mindControlPool(hacked: Boolean, own: Ship, target: Ship): List<AbstractCrew> =
            if (hacked) own.crew else target.crew

        /** CombatAI 0x62d710: the pulse needs the hacked system to be intact and the hacking system to be powered. */
        internal fun canStartPulse(hackedSystemBroken: Boolean, hackingPower: Int): Boolean =
            !hackedSystemBroken && hackingPower != 0

        /**
         * ShipSystem::GetPowerCap (0x63b790): an event's power limit (+0x11c below 8) or else the system's level
         * (+0x54). Damage, ion and a power loss leave it alone.
         */
        internal fun nativePowerCap(system: AbstractSystem): Int =
            system.scriptedPowerLimit ?: system.energyLevels

        /** 0x884f10: a missing escape timer means 15 seconds. */
        const val DEFAULT_ESCAPE_TIMER = 15f

        /** 0x884f34: seconds of stalemate before the ship gives up. */
        const val STALEMATE_SURRENDER_TIME = 120f

        /** 0x884f38: a stalemate makes the ship escape with at least this timer. */
        const val STALEMATE_ESCAPE_TIMER = 90f

        /** 0x884f3c: a hull change caps the stalemate timer at this. */
        const val STALEMATE_TIMER_CAP = 60f

        /** The escape threshold a stalemate sets (ShipAI +0xb0), above any hull. */
        const val STALEMATE_ESCAPE_HEALTH = 300

        /** 0x884f44: the escape timer's speed while the player has an FTL Jammer. */
        const val JAMMER_RATE = 0.5f

        /** 0x8850f8 / 0x885100: the medbay thresholds. */
        const val NEEDS_HEALING_LOW = 0.25f
        const val NEEDS_HEALING_CALM = 0.99f

        /** A pending jump keeps the timer just above zero so InGameState leaves it alone. */
        private const val MIN_PENDING_TIMER = 0.0001f

        private const val MAX_AIM_ATTEMPTS = 64
    }
}
