// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (CustomCreateLocation effects,
// EventDamageEnemy, ShipManager::RemoveItem) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see
// docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import ftl.browser.NativeRandom
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.weapons.Damage

/** The HS event actions that change ships. */
object HsEventEffects {
    /** `<resetFtl/>`: the FTL drive starts charging from zero. */
    fun resetFtl(ship: Ship) {
        ship.ftlChargeProgress = 0f
    }

    /** `<repairAllSystems/>`: every system of the player's ship loses its damage. */
    fun repairAllSystems(ship: Ship) {
        for (sys in ship.systems) sys.damagedEnergyLevels = 0
    }

    /**
     * `<removeHazards/>` (UpdateLocation): asteroids, pulsar, ASB, sun and storm stop (a nebula stays a calm nebula)
     * and the player's ship loses its event statuses (ShipManager::ClearStatusAll). The SpaceManager scene is not
     * rebuilt: it keeps its backdrop, planet and fleet, and makes the setter calls' draws on the global stream
     * ([xyz.znix.xftl.environment.AbstractEnvironment.hsRemoveHazardsFrom]).
     */
    fun removeHazards(game: InGameState) {
        val beacon = game.currentBeacon
        val old = beacon.getEnvironment(game)
        beacon.hsHazardsRemoved = true
        beacon.clearEnvironment()
        beacon.getEnvironment(game).hsRemoveHazardsFrom(old)
        val player = game.player ?: return
        beacon.powerLimitEffects.clear()
        beacon.powerLossEffects.clear()
        player.updateScriptedPowerLimits()
        xyz.znix.xftl.hs.systems.HsPower.status(player, true, xyz.znix.xftl.sector.EventStatus.Operation.CLEAR, 0)
        // if (space.bNebula) space.SetNebula(true) comes after ClearStatusAll in HS; it draws no ship state, so the
        // order of the clouds' draws against the status clear does not matter.
    }

    /**
     * `<removeNebula/>` (UpdateLocation): the scene is no nebula and no storm any more; backdrop, planet and fleet
     * stay.
     */
    fun removeNebula(game: InGameState) {
        val beacon = game.currentBeacon
        val old = beacon.getEnvironment(game)
        beacon.hsNebulaRemoved = true
        beacon.clearEnvironment()
        beacon.getEnvironment(game).hsRemoveNebulaFrom(old)
    }

    /** `<killEnemyBoarders/>`: intruders aboard the player's ship die. */
    fun killEnemyBoarders(game: InGameState) {
        val player = game.player ?: return
        for (crew in player.crew) {
            if (crew is LivingCrew && crew.ownerShip !== player) crew.health = 0f
        }
    }

    /**
     * CustomCreateLocation's enemy AI switches, in HS's order. ShipAI fields onto the engine: `escaping` is a running
     * escape timer, `surrendered` is [Ship.aiSurrendered], the escape/surrender thresholds are the hull values
     * [Ship.escapeHealth]/[Ship.surrenderHealth].
     * - instantEscape: escaping, then ShipManager::JumpLeave at once (InGameState.hsEnemyJumpLeave). removeShipBox
     *   only hides the box of a non-hostile ship after it left; the engine drops the box with the ship anyway.
     * - escape: escaping (the timer starts from the ship event's, 15 s when unset, as SetEscapeTimer).
     * - surrender: surrendered (no event is shown, as in HS).
     * - loadEscape/loadSurrender: the threshold goes to INT_MAX when forced or when the ship had one.
     * - disableEscape/disableSurrender: the threshold goes to 0; forced also clears escaping/surrendered.
     */
    fun enemyAi(game: InGameState, custom: CustomEvent) {
        val enemy = game.enemy ?: return
        val alive = !enemy.isDead
        if (custom.instantEscape && alive) {
            startEscape(enemy)
            game.hsEnemyJumpLeave()
        }
        if (custom.escape && alive) startEscape(enemy)
        if (custom.surrender && alive) enemy.aiSurrendered = true
        if (custom.loadEscape && (custom.loadEscapeForced || enemy.escapeHealth > 0)) enemy.escapeHealth = Int.MAX_VALUE
        if (custom.loadSurrender && (custom.loadSurrenderForced || enemy.surrenderHealth > 0)) enemy.surrenderHealth = Int.MAX_VALUE
        if (custom.disableEscape) {
            enemy.escapeHealth = 0
            if (custom.disableEscapeForced) enemy.escapeTimer = null
        }
        if (custom.disableSurrender) {
            enemy.surrenderHealth = 0
            if (custom.disableSurrenderForced) enemy.aiSurrendered = false
        }
    }

    private fun startEscape(enemy: Ship) {
        if (enemy.escapeTimer != null) return
        val t = enemy.spec?.escapeTimer ?: 0f
        enemy.escapeTimer = if (t > 0f) t else 15f
    }

    /**
     * RecallBoarders(direction, force, effects): direction >= 0 brings the player's crew aboard the enemy home,
     * direction <= 0 the enemy's crew aboard the player; each living non-drone crew member that can teleport
     * (CAN_TELEPORT, or force) empties its slot and is added to its own ship in the piloting room (a free slot
     * there, else anywhere). [effects] plays the arrival animation and the teleport sound (the augment path).
     */
    fun recallBoarders(game: InGameState, direction: Int, force: Boolean, effects: Boolean = false) {
        val player = game.player ?: return
        val enemy = game.enemy ?: return
        if (direction >= 0) recall(enemy, player, force, effects)
        if (direction <= 0) recall(player, enemy, force, effects)
    }

    /**
     * multiShipFix (WorldManager::UpdateLocation): RecallBoarders(1, false), then KillEveryone(true) on the replaced
     * ship (no clone bay, no corpses) and SetDestroyed; the caller drops it from play by swapping the enemy.
     */
    fun replaceEnemyShip(game: InGameState) {
        val enemy = game.enemy ?: return
        recallBoarders(game, 1, false)
        for (c in enemy.crew.toList()) {
            if (c is LivingCrew) c.health = 0f
            c.removeFromShip()
        }
        game.player?.sys?.updatePlayerCrew()
    }

    private fun recall(from: Ship, home: Ship, force: Boolean, effects: Boolean) {
        val target = home.piloting?.room
        for (c in from.crew.toList()) {
            if (c !is LivingCrew || c.health <= 0f || c.ownerShip !== home) continue
            val canTeleport = xyz.znix.xftl.hs.crew.HsCrew.ex(c).calculateBool(xyz.znix.xftl.hs.stat.CrewStat.CAN_TELEPORT, null)
            if (!canTeleport && !force) continue
            c.removeFromShip()
            home.crew.add(c)
            val mode = if (c.isMindControlled) xyz.znix.xftl.crew.AbstractCrew.SlotType.INTRUDER
            else xyz.znix.xftl.crew.AbstractCrew.SlotType.CREW
            c.jumpTo(home.findSpaceForCrew(target, mode))
            if (effects) {
                c.startTeleportArrive()
                HsEvents.playSound(home.sys, "teleport")
            }
        }
        home.sys.updatePlayerCrew()
    }

    /**
     * `<win>` / `<lose>` (ModifyResources): the sound, then the victory (ScoreKeeper::SetVictory, CommandGui::Victory)
     * or the defeat (CommandGui::CheckGameover) screen with the event's text (replaceGameOverText) and, for a victory,
     * its credits text, background and music.
     */
    fun gameOver(game: InGameState, def: EventGameOver) {
        if (def.victory) game.sounds.stopPlaylist(0.1f)
        if (def.sound.isNotEmpty()) HsEvents.playSound(game, def.sound)
        // replaceGameOverText: GameOver::OpenText shows the event's text (HsFinalBoss.takeGameOverText)
        HsFinalBoss.gameOverText = def.text
        // the victory's credits text, backdrop and music (render: hs.render.HsCredits)
        if (def.victory) xyz.znix.xftl.hs.render.HsCredits.victoryEvent(game, def.creditsText, def.creditsBackground, def.creditsMusic)
        game.shipUI.showGameOverScreen(
            if (def.victory) xyz.znix.xftl.game.GameOverWindow.Outcome.WIN else xyz.znix.xftl.game.GameOverWindow.Outcome.LOOSE_HULL
        )
    }

    /**
     * `<superShields player amount add>`: on that ship's shield system, the super shield maximum rises to amount,
     * the super shield to amount (to the maximum when amount is -1), then add more, capped at the maximum.
     */
    fun superShields(game: InGameState, custom: CustomEvent) {
        val ship = (if (custom.powerSuperShields == 0) game.player else game.enemy) ?: return
        if (ship.shields == null) return
        val set = custom.powerSuperShieldsSet
        if (set > ship.maxSuperShield) ship.maxSuperShield = set
        if (set == -1) ship.superShield = ship.maxSuperShield
        else if (set > ship.superShield) ship.superShield = set
        ship.superShield = minOf(ship.maxSuperShield, ship.superShield + custom.powerSuperShieldsAdd)
    }

    /**
     * `<enemyDamage>` (EventDamageEnemy): hull damage unless effect has 16, then system damage in the system's room
     * (18: a random system's room, 19: a random room; effect 8 damages the system directly), then fire/breach
     * (effect 4: one of them at random). The eventDamage sound plays when the event has no player damage.
     */
    fun enemyDamage(game: InGameState, event: CreatedEvent, custom: CustomEvent) {
        if (event.template.hullDamage.isEmpty()) HsEvents.playSound(game, "eventDamage")
        for (d in custom.enemyDamage) damageEnemy(game, d)
    }

    private fun damageEnemy(game: InGameState, d: HsEventDamage) {
        val enemy = game.enemy ?: return
        if (game.isEnemyJumping) return
        if (d.effect and 16 == 0) enemy.health = enemy.health - d.amount
        if (d.system == -1 && d.systemName == null) return
        val room = when (d.systemName) {
            "random" -> if (enemy.systems.isNotEmpty()) enemy.systems[NativeRandom.next31() % enemy.systems.size].room
            else enemy.rooms[NativeRandom.next31() % enemy.rooms.size]
            "room" -> enemy.rooms[NativeRandom.next31() % enemy.rooms.size]
            null -> null
            else -> enemy.systems.firstOrNull { it.codename == d.systemName }?.room
        } ?: return
        if (d.effect and 8 != 0) {
            val sys = enemy.systems.firstOrNull { it.room === room }
            if (sys != null) sys.damagedEnergyLevels = (sys.damagedEnergyLevels + d.amount).coerceIn(0, sys.energyLevels)
        } else {
            val damage = Damage()
            damage.pureSysDamage = d.amount
            enemy.damage(room, damage, null)
        }
        if (d.effect and 4 != 0) {
            if (NativeRandom.next31() and 1 != 0) room.spawnFire() else room.spawnBreach()
        } else {
            if (d.effect and 1 != 0) room.spawnFire()
            if (d.effect and 2 != 0) room.spawnBreach()
        }
    }

    /**
     * ShipManager::RemoveItem (HS priority 9999): an augment (or a "HIDDEN x"), then the first weapon and the first
     * drone of that name, and only when nothing went and checkCargo is on, one in the cargo hold.
     */
    fun removeItem(game: InGameState, name: String) {
        val ship = game.player ?: return
        var removed = false
        if (name.startsWith("HIDDEN ")) {
            val n = HsEvents.hiddenAugs[name] ?: 0
            if (n > 1) HsEvents.hiddenAugs[name] = n - 1 else HsEvents.hiddenAugs.remove(name)
            // The ship's own hidden augments (hs.ship: augment values and functions) follow the same list.
            ship.hs?.removeHidden(name.removePrefix("HIDDEN "))
            removed = true
        } else {
            val aug = ship.augments.firstOrNull { it.name == name }
            if (aug != null) {
                ship.augments.remove(aug)
                ship.cargoUpdated()
                removed = true
            }
        }
        val hp = ship.hardpoints.firstOrNull { it.weapon?.type?.name == name }
        if (hp != null) {
            hp.weapon = null
            ship.cargoUpdated()
            removed = true
        }
        ship.drones?.let { drones ->
            val index = drones.drones.indexOfFirst { it?.type?.name == name }
            if (index >= 0) {
                drones.drones[index]?.instance?.removeInstance()
                drones.drones[index] = null
                ship.cargoUpdated()
                removed = true
            }
        }
        if (!removed && HsEvents.checkCargo) {
            // CustomEvents.cpp: the cargo boxes (every page), then UpdateOverCapacityItems.
            val item = ship.allCargo().firstOrNull { it.name == name }
            if (item != null) ship.removeBlueprint(item)
        }
    }
}
