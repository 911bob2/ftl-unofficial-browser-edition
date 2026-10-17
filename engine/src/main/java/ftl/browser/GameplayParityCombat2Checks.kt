package ftl.browser

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.SaveProfile
import xyz.znix.xftl.game.ResourceSet
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Cloaking
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.weapons.Damage

/**
 * Round-2 combat residues: the locked-down door that nothing breaks (Door::ApplyDamage 0x46e0f0 has no lockdown input),
 * the crew weapon owner gate (ShipManager::DamageCrew 0x49cf80), the skill experience grid (CrewMember::IncreaseSkill
 * 0x479b90) and the ion hit that ends a permanent lock (ShipSystem::IonDamage 0x63d830 / AddLock 0x63bd80).
 * Algorithmic known answers on controlled fixtures.
 */
internal object GameplayParityCombat2Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val lockdown = lockdownDoor(main, container)
            val owner = crewOwnerGate(main, container)
            val skill = skillGrid(main, container)
            val lock = permanentLock(main, container)
            val arm = repairArmJump(main, container)
            val airlock = airlockVenting(main, container)
            val dodge = dodgeGate(main, container)
            val lockSave = lockOverrideSave(main, container)
            val rock = rockArmourHull(main, container)
            val achievements = achievementUpgrade(main)
            val zoltan = zoltanLossAndRequeue(main, container)
            val shieldsStep = shieldRepowerStep(main, container)
            return "combat2 ok: $lockdown, $owner, $skill, $lock, $arm, $airlock, $dodge, $lockSave, $rock, $achievements, $zoltan, $shieldsStep"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * Door::SetLockdown(true) (0x4708a0, called from the lockdown creation 0x494f50) gives the door 50 hit points,
     * which Door::ApplyDamage (0x46e0f0) wears down one per hit; at zero the door opens and the lockdown ends for it.
     * Without hits it holds until the timer ends.
     */
    private fun lockdownDoor(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val door = state.player.doors.first { !it.isAirlock }
        door.resetHealth()
        door.lockdown()
        check(door.lockdownTimer == 12f && !door.open) { "Lockdown: timer ${door.lockdownTimer} open ${door.open}" }
        repeat(49) { door.attackDoor() }
        check(door.lockdownTimer > 0f && door.damage == 0 && !door.open) {
            "A locked door gave way early: timer ${door.lockdownTimer} damage ${door.damage} open ${door.open}"
        }
        door.attackDoor()
        check(door.lockdownTimer == 0f && door.open) { "A locked door survived 50 hits" }
        return "lockdown door"
    }

    /**
     * Crew weapon damage skips the owner's own crew unless the damage is friendly fire, which is what the anti-ship
     * battery sets. Other ships' crew are always hurt.
     */
    private fun crewOwnerGate(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val crew = player.crew.filterIsInstance<LivingCrew>().first { it.health > 0f }

        val own = Damage()
        own.ownerShip = player
        val before = crew.health
        check(!player.damageCrewMember(crew, 5f, own) && crew.health == before) { "Own weapon hurt its crew" }

        own.friendlyFire = true
        player.damageCrewMember(crew, 5f, own)
        check(crew.health == before - 5f) { "Friendly fire missed: ${crew.health} from $before" }

        val none = Damage()
        val mid = crew.health
        player.damageCrewMember(crew, 5f, none)
        check(crew.health == mid - 5f) { "Ownerless damage skipped the crew: ${crew.health} from $mid" }
        return "crew owner gate"
    }

    /**
     * Experience is an integer pair (xp, twice the actions per colour change): every action is one xp, maxing out
     * stops at the maximum and does not count past it.
     */
    private fun skillGrid(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val crew = state.player.crew.filterIsInstance<LivingCrew>().first()
        val skill = Skill.PILOTING
        crew.info.skills[skill] = 0f
        val maxXp = skill.actionsPerLevel(crew.blueprint) * 2

        repeat(maxXp - 1) { crew.addSkillPoint(skill) }
        check(!crew.isSkillMaxed(skill)) { "Skill maxed one point early" }
        crew.addSkillPoint(skill)
        check(crew.isSkillMaxed(skill) && crew.info.skills.getValue(skill) == 1f) { "Skill not maxed at $maxXp points" }
        crew.addSkillPoint(skill)
        check(crew.info.skills.getValue(skill) == 1f) { "Skill went past the maximum" }
        return "skill grid"
    }

    /**
     * An active cloak locks its power for good; an ion hit overwrites that lock with its own, so the permanent lock
     * ends while the cloak keeps running. A forced decrease works through the cloak's lock.
     */
    private fun permanentLock(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_STEALTH")
        val cloak = state.player.systems.filterIsInstance<Cloaking>().first()
        if (cloak.powerSelected == 0) cloak.increasePower()
        cloak.activateCloak()
        check(cloak.active && cloak.hasPermanentLock && cloak.isPowerLocked) { "An active cloak is not locked" }

        val power = cloak.powerSelected
        cloak.forceDecreasePower()
        check(cloak.powerSelected == power - 1) { "Forced decrease on a locked cloak: ${cloak.powerSelected} from $power" }

        cloak.dealDamage(0, 1)
        check(cloak.active && !cloak.hasPermanentLock && cloak.isIonised) {
            "Ion on the cloak: active ${cloak.active} permanent ${cloak.hasPermanentLock} ion ${cloak.isIonised}"
        }
        return "permanent lock"
    }

    /** The Scrap Recovery Arm heals two hull on a reward, but not while the jump-out / arrival animation runs. */
    private fun repairArmJump(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.player.addBlueprint(state.blueprintManager["REPAIR_ARM"] as AugmentBlueprint, true)
        state.player.health = state.player.maxHealth - 6
        state.givePlayerResources(ResourceSet().also { it.scrap = 10 })
        check(state.player.health == state.player.maxHealth - 4) { "Arm at rest: ${state.player.health}" }

        check(!state.isPlayerJumping) { "Jumping at rest" }
        state.beginJumpOut(state.currentBeacon)
        check(state.isPlayerJumping) { "Not jumping after beginJumpOut" }
        state.givePlayerResources(ResourceSet().also { it.scrap = 10 })
        check(state.player.health == state.player.maxHealth - 4) { "Arm healed while jumping: ${state.player.health}" }
        return "repair arm jump gate"
    }

    /**
     * An open airlock door empties a room holding more than 10% oxygen at once; at or below that it leaves the air to
     * the ordinary leak.
     */
    private fun airlockVenting(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val door = state.player.doors.firstOrNull { it.isAirlock }
            ?: return "airlock venting (no airlock)"
        val room = door.left ?: door.right!!

        room.oxygen = 0.5f
        door.open = true
        door.update(0f)
        check(room.oxygen == 0f) { "Airlock left ${room.oxygen} oxygen" }

        room.oxygen = 0.05f
        door.open = true
        door.update(0f)
        check(room.oxygen == 0.05f) { "Airlock vented a room at 5%: ${room.oxygen}" }
        return "airlock venting"
    }

    /**
     * The dodge experience needs an enemy ship or a living intruder aboard (ShipManager+0x7f0 > 0): a miss on a lone
     * player ship trains nothing.
     */
    private fun dodgeGate(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        if (state.getEnemyOf(player) != null || player.intruders.isNotEmpty())
            return "dodge gate (enemy present, skipped)"

        val before = player.skillEvents.dodge
        var misses = 0
        for (i in 0 until 5) {
            if (player.pickMissed(missDeadEnemies = false, deltaEvasion = 1000))
                misses++
        }
        check(misses > 0) { "No dodge rolled" }
        check(player.skillEvents.dodge == before) { "A lone ship trained from dodges: ${player.skillEvents.dodge}" }
        return "dodge gate"
    }

    /** The ion override of a permanent lock survives a save, and old saves without it load unlocked. */
    private fun lockOverrideSave(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_STEALTH")
        val cloak = state.player.systems.filterIsInstance<Cloaking>().first()
        // Power 3, so the one ion unit leaves two bars: a cloak left on no power would end by itself.
        state.player.purchasedReactorPower = 25
        cloak.energyLevels = 3
        repeat(3) { cloak.increasePower(); state.player.update(0f) }
        check(cloak.powerSupplied == 3) { "Cloak power ${cloak.powerSupplied}" }
        cloak.activateCloak()
        cloak.dealDamage(0, 1)
        check(cloak.active && !cloak.hasPermanentLock) { "Override not set" }

        val reloaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val again = reloaded.player.systems.filterIsInstance<Cloaking>().first()
        check(again.active && !again.hasPermanentLock) {
            "Override lost in the save: active ${again.active} permanent ${again.hasPermanentLock}"
        }
        return "lock override save"
    }

    /**
     * A beam step that rolled Rock Plating takes no hull damage, one that did not does (ShipManager::DamageBeam 0x4a9d40
     * hands the single roll of the step to the room damage).
     */
    private fun rockArmourHull(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val room = player.rooms.first()
        val full = player.health

        val hit = Damage()
        hit.hullDamage = 2
        player.damage(room, hit, null, true)
        check(player.health == full) { "A rolled Rock Plating step still hurt the hull: ${player.health}" }
        player.damage(room, hit, null, false)
        check(player.health == full - 2) { "An unrolled step missed the hull: ${player.health}" }
        return "rock armour per step"
    }

    /**
     * AchievementTracker::SetAchievement (0x432010): an achievement that is not multi-difficulty keeps its first
     * difficulty, a multi-difficulty one only moves up.
     */
    private fun achievementUpgrade(main: MainGame): String {
        val table = main.gameContent.achievements.achievements.values
        val single = table.first { !it.isMultiDifficulty }
        val multi = table.first { it.isMultiDifficulty }
        val profile = SaveProfile.createBlank()

        profile.unlockAchievement(single, Difficulty.NORMAL)
        profile.unlockAchievement(single, Difficulty.HARD)
        check(profile.getAchievement(single)!!.difficulty == Difficulty.NORMAL) { "Single achievement upgraded" }

        profile.unlockAchievement(multi, Difficulty.NORMAL)
        profile.unlockAchievement(multi, Difficulty.HARD)
        check(profile.getAchievement(multi)!!.difficulty == Difficulty.HARD) { "Multi achievement not upgraded" }
        profile.unlockAchievement(multi, Difficulty.EASY)
        check(profile.getAchievement(multi)!!.difficulty == Difficulty.HARD) { "Multi achievement downgraded" }
        return "achievement upgrade"
    }

    /**
     * A Zoltan that leaves a system the reactor can't make up for takes the power with him for good: a bar freed
     * elsewhere afterwards is not taken (CheckForRepower1 0x63ac40 overwrites the target). A powered weapon that loses
     * part of its Zoltan share is switched off and gets one re-power attempt (hook 0x6363a0, OnLoop 0x637190).
     */
    private fun zoltanLossAndRequeue(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val info = LivingCrewInfo.generateRandom(state.blueprintManager["energy"] as CrewBlueprint, state)
        val home = player.rooms.sortedBy { it.cellCount }.first { it.system == null }

        fun place(crew: LivingCrew, room: xyz.znix.xftl.layout.Room) {
            crew.jumpTo(room, room.slotToPoint(0))
            player.rooms.forEach { it.updateCrewInRoom() }
            player.updateAvailablePower()
        }

        // Engines: lose the Zoltan with no spare reactor power.
        val engines = player.engines!!
        val zoltan = player.addCrewMemberInRoom(info, engines.room!!, false)
        place(zoltan, engines.room!!)
        check(engines.forcedPower >= 1) { "No Zoltan power in the engine room" }
        // A reactor exactly as big as the demand (the Zoltan bars come on top of it): no spare bar anywhere.
        player.purchasedReactorPower = player.powerConsumed - player.systems.filterIsInstance<MainSystem>().sumOf { it.forcedPower }
        player.updateAvailablePower()
        check(player.powerAvailable == 0) { "Could not use up the reactor: ${player.powerAvailable}" }
        place(zoltan, home)
        check(engines.powerSelected == engines.powerSupplied) {
            "Engines kept a demand of ${engines.powerSelected} on ${engines.powerSupplied}"
        }
        val kept = engines.powerSelected
        player.systems.filterIsInstance<MainSystem>().first { it !== engines && it.powerSelected > 0 }.decreasePower()
        player.updateAvailablePower()
        check(engines.powerSelected == kept) { "Engines took back power later: ${engines.powerSelected} vs $kept" }

        // Weapons: a Zoltan share lost from a powered weapon.
        val weapons = player.weapons!!
        val weaponRoom = weapons.room!!
        val power = weapons.powerManager
        val slot = (0 until player.hardpoints.size).firstOrNull { player.hardpoints[it].weapon != null }
            ?: return "zoltan loss (no weapons)"
        val weapon = player.hardpoints[slot].weapon!!
        place(zoltan, weaponRoom)
        check(weapons.forcedPower >= 1) { "No Zoltan power in the weapons room" }
        power.setItemPower(slot, true)
        check(weapon.isPowered) { "Weapon did not power on" }
        // Make sure the weapon can come back: some spare reactor power.
        for (system in player.systems.filterIsInstance<MainSystem>()) {
            if (system !== weapons && system.powerSelected > 0 && player.powerAvailable < 3) system.decreasePower()
        }
        player.updateAvailablePower()
        place(zoltan, home)
        if (weapon.isPowered)
            return "zoltan loss (weapon needed no change)"
        power.update()
        check(weapon.isPowered || player.powerAvailable == 0) { "Queued weapon was not given its attempt" }
        power.update()
        return "zoltan loss and weapon requeue"
    }

    /**
     * Shields repower in pairs (CheckForRepower1 0x63ac40): with one level damaged (an odd cap of 7) a system on 6 is
     * not topped up to an idle 7th bar, and the goal it was after becomes what it has.
     */
    private fun shieldRepowerStep(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val shields = player.shields!!
        if (shields.energyLevels < 8)
            return "shield repower step (small shields, skipped)"

        for (system in player.systems.filterIsInstance<MainSystem>()) {
            if (system !== shields) repeat(8) { system.decreasePower() }
        }
        player.updateAvailablePower()
        repeat(4) { shields.increasePower() }
        if (shields.powerSelected != 8)
            return "shield repower step (no power to raise, skipped)"

        shields.decreasePower()
        shields.decreasePower()
        shields.damagedEnergyLevels = 1
        shields.targetPower = 8
        shields.powerLimitChanged()
        check(shields.powerSelected == 4 || shields.powerSelected == 6) { "Shields on ${shields.powerSelected}" }
        check(shields.powerSelected % 2 == 0 && shields.targetPower == shields.powerSelected) {
            "Shields took an idle bar: power ${shields.powerSelected} target ${shields.targetPower}"
        }
        return "shield repower step"
    }
}
