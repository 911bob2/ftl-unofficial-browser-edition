// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (the `<finalBoss>` /
// `<miniBoss>` ship event: WorldManager::OnLoop and CompleteShip::DeadCrew hooks, alreadyWonCustom, replaceGameOverText,
// GameOver::OpenText, and their part of StarMap::SaveGame / LoadGame). FTL-Hyperspace is GPL-3.0-or-later; this engine
// is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.events

import xyz.znix.xftl.game.InGameState

/**
 * A ship event with `<finalBoss>` (or `<miniBoss>`): when the enemy of that event is destroyed (WorldManager::OnLoop) or
 * its crew dies (CompleteShip::DeadCrew), once per ship, its sound plays and, for `<finalBoss>`, the run is won:
 * CommandGui::alreadyWon and alreadyWonCustom are set and the boss's text, credits text, backdrop and music replace the
 * game over ones. The run goes on; the next defeat (crew gone, or the ship's explosion over while the enemy's is) opens
 * the victory instead (oracle sweep-world-y1: the save after DamageHull holds alreadyWon, alreadyWonCustom, the text and
 * the music).
 */
object HsFinalBoss {
    private var bossDefeated = false
    @JvmField var alreadyWon = false
    @JvmField var alreadyWonCustom = false
    /** replaceGameOverText: GameOver::OpenText shows it once instead of its own text. */
    @JvmField var gameOverText = ""
    /** CustomShipUnlocks::setCustomVictoryType (the `ach` of the boss). */
    @JvmField var victoryType = ""

    /** StarMap::NewGame / LoadGame, WorldManager::CreateShip: the boss of the next ship is not yet defeated. */
    @JvmStatic
    fun shipCreated() { bossDefeated = false }

    /** A new run clears the victory state (the CustomEvents.cpp new-game reset). */
    @JvmStatic
    fun newGame() {
        bossDefeated = false; alreadyWon = false; alreadyWonCustom = false; gameOverText = ""; victoryType = ""
    }

    /** WorldManager::OnLoop (unpaused): the current ship event's enemy destroyed. */
    @JvmStatic
    fun onLoop(game: InGameState) {
        if (bossDefeated) return
        val enemy = game.enemy ?: return
        if (!enemy.isDead) return
        // the destroyed boss explodes for 6 s (enemy->ship.explosion.time = 6.0)
        if (check(game, enemy)) enemy.hsDestructionTime = 6f
    }

    /** CompleteShip::DeadCrew for the enemy. */
    @JvmStatic
    fun deadCrew(game: InGameState) {
        if (bossDefeated) return
        check(game, game.enemy ?: return)
    }

    private fun check(game: InGameState, enemy: xyz.znix.xftl.Ship): Boolean {
        val name = enemy.spec?.name ?: return false
        val custom = game.eventManager.hs?.getCustomShipEvent(name) ?: return false
        val def = custom.finalBoss
        if (!def.enabled) return false
        bossDefeated = true
        if (def.sound.isNotEmpty()) HsEvents.playSound(game, def.sound)
        if (def.victory) {
            alreadyWon = true
            alreadyWonCustom = true
            gameOverText = def.text
            xyz.znix.xftl.hs.render.HsCredits.victoryEvent(game, def.creditsText, def.creditsBackground, def.creditsMusic)
            victoryType = def.ach
        }
        return true
    }

    /** WorldManager::OnLoop: with the custom victory won, a defeat is the victory (the enemy's explosion over, if any). */
    @JvmStatic
    fun defeatIsVictory(game: InGameState): Boolean {
        if (!alreadyWonCustom || !alreadyWon) return false
        val enemy = game.enemy
        return enemy == null || !enemy.isDead || enemy.isGone
    }

    /** GameOver::OpenText: the replaced text, once. */
    @JvmStatic
    fun takeGameOverText(): String? = gameOverText.takeIf { it.isNotEmpty() }?.also { gameOverText = "" }

    @JvmStatic
    fun registerSave() {
        xyz.znix.xftl.hs.save.HsSave.register("finalBoss", xyz.znix.xftl.hs.save.HsSave.Scope.RUN, { e ->
            e.setAttribute("alreadyWon", alreadyWon.toString()).setAttribute("alreadyWonCustom", alreadyWonCustom.toString())
                .setAttribute("text", gameOverText).setAttribute("victoryType", victoryType)
        }, { e ->
            bossDefeated = false
            alreadyWon = e?.getAttributeValue("alreadyWon") == "true"
            alreadyWonCustom = e?.getAttributeValue("alreadyWonCustom") == "true"
            gameOverText = e?.getAttributeValue("text") ?: ""
            victoryType = e?.getAttributeValue("victoryType") ?: ""
        })
    }
}
