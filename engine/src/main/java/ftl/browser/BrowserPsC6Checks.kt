package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.HsDamage
import xyz.znix.xftl.hs.crew.addCrewMemberFromString
import xyz.znix.xftl.hs.crew.damageArea
import xyz.znix.xftl.hs.crew.luaKill
import xyz.znix.xftl.hs.crew.toDamage

/**
 * Parity sweep C6 regression checks, run by [BrowserStatCrewDiagnostics] on a Hyperspace ship (the hs-statcrew job):
 * one `{"name", "ok", "detail"}` entry each. Every crew member a check adds is removed again.
 */
object BrowserPsC6Checks {
    private fun q(s: String) = BrowserDebug.quote(s)

    /**
     * C6-C1: a crew power with `<win>true</win>` wins the run on the spot (HS CrewAbilities.cpp ActivatePower:
     * StopPlaylist(100), the victory sound, SetVictory, the custom victory type cleared, SetVictoryAchievement, gameover,
     * CommandGui::Victory). Its own run, which it ends: unique_tully's power with win set for the check.
     */
    fun win(main: xyz.znix.xftl.game.MainGame): String {
        val detail = try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", xyz.znix.xftl.game.Difficulty.NORMAL)
            val state = main.currentState as InGameState
            if (!CustomCrewManager.isRace("unique_tully")) return "[]" // not Multiverse
            val c = HsCrew.addCrewMemberFromString(state.player, "C6 win", "unique_tully", false, 0, true, true)
            val p = HsCrew.ex(c).crewPowers.first()
            val sounds = state.content.sounds as? xyz.znix.xftl.game.RealSoundManager
            val played = ArrayList<String>()
            sounds?.traceSink = { kind, name -> played += "$kind $name" }
            xyz.znix.xftl.hs.events.HsFinalBoss.victoryType = "PSC6"
            val wins = listOf("ACH_WIN_EASY", "ACH_WIN_NORMAL").mapNotNull { state.content.achievements.achievements[it] }
                .filter { main.profile.getAchievement(it) == null }
            val typed = xyz.znix.xftl.hs.ship.HsShipUnlocks.checkCustomShipVictory("PLAYER_SHIP_MVKESTREL", "flagship")
            val old = p.def.win
            p.def.win = true
            try {
                p.activatePower()
            } finally {
                p.def.win = old
                sounds?.traceSink = null
            }
            val outcome = state.browserProgress.outcome
            val window = state.shipUI.diagnosticWindow
            when {
                outcome != xyz.znix.xftl.game.GameOverWindow.Outcome.WIN -> "outcome $outcome"
                window != "GameOverWindow" -> "window $window"
                sounds != null && "play victory" !in played -> "sounds $played"
                xyz.znix.xftl.hs.events.HsFinalBoss.victoryType != "" -> "victory type " + xyz.znix.xftl.hs.events.HsFinalBoss.victoryType
                xyz.znix.xftl.hs.ship.HsShipUnlocks.checkCustomShipVictory("PLAYER_SHIP_MVKESTREL", "flagship") != typed -> "typed flagship victory awarded"
                xyz.znix.xftl.hs.ship.HsShipUnlocks.setCustomVictoryType != "flagship" -> "victory type not reset: " + xyz.znix.xftl.hs.ship.HsShipUnlocks.setCustomVictoryType
                wins.any { main.profile.getAchievement(it) != null } -> "awarded " + wins.filter { main.profile.getAchievement(it) != null }.map { it.id }
                else -> null
            }
        } catch (e: Throwable) {
            "exception " + e + " " + e.stackTrace.take(3).joinToString(" < ")
        } finally {
            main.switchToMainMenu()
        }
        return "[{\"name\":\"a win power wins the run\",\"ok\":" + (detail == null) + ",\"detail\":" + q(detail ?: "") + "}]"
    }

    fun run(state: InGameState): String {
        val out = ArrayList<String>()
        fun check(name: String, body: () -> String?) {
            val detail = try { body() } catch (e: Throwable) { "exception " + e + " " + e.stackTrace.take(3).joinToString(" < ") }
            out += "{\"name\":" + q(name) + ",\"ok\":" + (detail == null) + ",\"detail\":" + q(detail ?: "") + "}"
        }
        val rng = BrowserRandom.snapshot()
        val queue = ArrayList(xyz.znix.xftl.hs.events.HsEvents.eventQueue)
        try {
            // F1: an ability's <event> goes to CustomEventsParser's queue when the power activates
            // (ActivatedPower::ActivatePower: QueueEvent(def->event[ownerShip], -1)); Multiverse's unique_tully.
            check("ability event queued") {
                if (!CustomCrewManager.isRace("unique_tully")) return@check null // not Multiverse
                val c = HsCrew.addCrewMemberFromString(state.player, "C6 tully", "unique_tully", false, 0, true, true)
                try {
                    val p = HsCrew.ex(c).crewPowers.firstOrNull() ?: return@check "unique_tully has no power"
                    if (p.def.event[0] != "TULLY_CALL_AIRSTRIKE") return@check "event[0] = " + p.def.event[0]
                    xyz.znix.xftl.hs.events.HsEvents.eventQueue.clear()
                    p.preparePower()
                    val q = xyz.znix.xftl.hs.events.HsEvents.eventQueue.map { it.event + "/" + it.label + "/" + it.seed }
                    if (q != listOf("TULLY_CALL_AIRSTRIKE//-1")) "queue " + q else null
                } finally {
                    c.removeFromShip()
                }
            }
            // F2: a power's room damage on its own ship without shipFriendlyFire hits each crew member of the room with
            // the whole Damage, (iDamage + persDamage) x 15 (CustomCrew.cpp DamageArea hook, priority 200; oracle
            // ps-c6-pw dm: damage 1 + persDamage 2 takes an intruder from 100 to 55).
            check("own-ship power damage hits crew with iDamage + persDamage") {
                val x = HsCrew.addCrewMemberFromString(state.player, "C6 dmx", "human", true, 0, true, true)
                try {
                    val room = x.room
                    room.updateCrewInRoom() // the room's list is rebuilt by the ship's update; the check runs between
                    val before = x.health
                    val dmg = HsCrew.toDamage(HsDamage(iDamage = 1, iPersDamage = 2), state.player)
                    HsCrew.damageArea(state.player, room, dmg, false)
                    val lost = before - x.health
                    if (Math.abs(lost - 45f) > 0.01f) "lost " + lost + " (want 45)" else null
                } finally {
                    val room = x.room
                    x.removeFromShip()
                    room.updateCrewInRoom()
                }
            }
            // F3: modifiedChargesPerJump is a float in HS, so the default chargesPerJump 1073741823 adds 2^30 on a jump:
            // an uncharged power goes from 2147483646 to -1073741826 (CrewMember::_HS_ResetPower; oracle ps-c6-js).
            check("charges per jump as a float") {
                if (!CustomCrewManager.isRace("unique_tully")) return@check null // not Multiverse
                val c = HsCrew.addCrewMemberFromString(state.player, "C6 jump", "unique_tully", false, 0, true, true)
                try {
                    val ex = HsCrew.ex(c)
                    val p = ex.crewPowers.firstOrNull() ?: return@check "unique_tully has no power"
                    if (p.def.chargesPerJump != 1073741823) return@check "chargesPerJump " + p.def.chargesPerJump
                    p.powerCharges.first = 2147483646
                    p.powerCharges.second = -1
                    ex.resetPower()
                    if (p.powerCharges.first != -1073741826) "charges " + p.powerCharges.first + " (want -1073741826)" else null
                } finally {
                    c.removeFromShip()
                }
            }
            // F4: a dying crew member keeps giving its boosts until bDead (StatBoostManager::OnLoop takes !bDead crew,
            // CrewMember::Functional is !IsDead(), 1.6.9 0x720b00; oracle ps-c6-pw dc). unique_tully: allies in its room
            // move at 1.25.
            check("a dying crew member's boosts last until bDead") {
                if (!CustomCrewManager.isRace("unique_tully")) return@check null // not Multiverse
                val s = HsCrew.addCrewMemberFromString(state.player, "C6 src", "unique_tully", false, 7, true, true)
                val a = HsCrew.addCrewMemberFromString(state.player, "C6 ally", "human", false, 7, true, true)
                fun speed(): Float {
                    xyz.znix.xftl.hs.stat.StatBoostManager.onLoop(state)
                    val ex = HsCrew.ex(a)
                    return ex.calculateStat(xyz.znix.xftl.hs.stat.CrewStat.MOVE_SPEED_MULTIPLIER, ex.definition())
                }
                try {
                    if (s.room !== a.room) return@check "not in one room"
                    val alive = speed()
                    val health = s.health
                    s.health = 0f
                    val dying = speed()
                    s.hsKilled = true
                    val dead = speed()
                    s.hsKilled = false
                    s.health = health
                    if (alive != 1.25f || dying != 1.25f || dead != 1f) "alive $alive dying $dying dead $dead (want 1.25 1.25 1)" else null
                } finally {
                    s.removeFromShip()
                    a.removeFromShip()
                    xyz.znix.xftl.hs.stat.StatBoostManager.onLoop(state)
                }
            }
            // F5: extraConditions idle is !IsBusy() (1.6.9 0x46fa20: no repair target, not fighting, on its slot's spot,
            // not frozen), so a crew member manning a station is idle (oracle ps-c6-en rc); moving is !AtFinalGoal().
            check("manning counts as idle") {
                val c = HsCrew.addCrewMemberFromString(state.player, "C6 idle", "human", false, 7, true, true)
                try {
                    val ex = HsCrew.ex(c)
                    val idle = xyz.znix.xftl.hs.crew.CrewExtraCondition.IDLE
                    val moving = xyz.znix.xftl.hs.crew.CrewExtraCondition.MOVING
                    if (c.pathingTarget != null || c.standingPosition == null) return@check "not on its slot"
                    val manning = ex.checkExtraCondition(idle, xyz.znix.xftl.crew.AbstractCrew.Action.MANNING)
                    val fighting = ex.checkExtraCondition(idle, xyz.znix.xftl.crew.AbstractCrew.Action.FIGHTING)
                    val repairing = ex.checkExtraCondition(idle, xyz.znix.xftl.crew.AbstractCrew.Action.REPAIRING)
                    val movingAtSlot = ex.checkExtraCondition(moving, xyz.znix.xftl.crew.AbstractCrew.Action.IDLE)
                    if (!manning || fighting || repairing || movingAtSlot)
                        "manning $manning fighting $fighting repairing $repairing moving $movingAtSlot (want true false false false)" else null
                } finally {
                    c.removeFromShip()
                }
            }
            // C2's note: Lua CrewMember::Kill ends the crew member's fight (1.6.9 0x4701c0 clears bFighting and the repair
            // target), so a killed fighter's queued body is saved without a target on a ship that may be gone.
            check("Lua Kill ends the fight") {
                val c = HsCrew.addCrewMemberFromString(state.player, "C6 kill", "human", false, 7, true, true)
                val x = HsCrew.addCrewMemberFromString(state.player, "C6 foe", "human", true, 7, true, true)
                try {
                    c.enemyToAttack = x
                    HsCrew.luaKill(c, false)
                    if (c.enemyToAttack != null || !c.hsKilled || c.health != 0f) "target " + c.enemyToAttack + " killed " + c.hsKilled else null
                } finally {
                    c.hsKilled = false
                    c.removeFromShip()
                    x.removeFromShip()
                }
            }
        } finally {
            xyz.znix.xftl.hs.events.HsEvents.eventQueue.clear()
            xyz.znix.xftl.hs.events.HsEvents.eventQueue.addAll(queue)
            BrowserRandom.restore(rng)
        }
        return out.joinToString(",", "[", "]")
    }
}
