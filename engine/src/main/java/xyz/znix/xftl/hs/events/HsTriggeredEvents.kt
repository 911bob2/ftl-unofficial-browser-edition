// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/TriggeredEvents.cpp and CustomEvents.h (TriggeredEvent,
// TriggeredEventModifier) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.sector.HsSeeds

/**
 * TimerHelper as a triggered event uses it (0x5cb1b0 Update: `currTime += speedFactor / 16` while running, i.e. the
 * frame's game seconds; 0x5cb200 Done: past the goal, then it stops; the triggered timer does not loop).
 */
class HsTriggerTimer {
    @JvmField var currTime = 0f
    @JvmField var currGoal = 0f
    @JvmField var running = false

    fun update(dt: Float) {
        if (running) currTime += dt
    }

    fun done(): Boolean {
        if (!running) return false
        if (currGoal <= currTime && currTime != currGoal) {
            running = false
            return true
        }
        return false
    }
}

/** A running triggered event (TriggeredEvent). The constructor draws like HS's (seed, loops, then [reset]). */
class HsTriggeredEvent private constructor(@JvmField val def: TriggeredEventDefinition) {
    @JvmField var seed = -1
    @JvmField var loops = 0
    @JvmField var triggerTimer: HsTriggerTimer? = null
    @JvmField var timerSoundIndex = 0
    @JvmField var triggerJumps = 0
    @JvmField var triggerPlayerHull = 0
    @JvmField var triggerEnemyHull = 0
    @JvmField var triggerPlayerCrew = 0
    @JvmField var triggerEnemyCrew = 0
    @JvmField var triggerPlayerDamage = 0
    @JvmField var triggerEnemyDamage = 0
    @JvmField var triggerPlayerDeaths = 0
    @JvmField var triggerEnemyDeaths = 0
    @JvmField var currentPlayerHull = 0
    @JvmField var currentEnemyHull = 0
    @JvmField var currentPlayerCrew = 0
    @JvmField var currentEnemyCrew = 0
    @JvmField var reqMinLvl = 0
    @JvmField var reqMaxLvl = 0
    @JvmField var reqLvl = 0
    @JvmField var triggered = false

    /** WarningMessage: shown (the render workstream draws it) once the time left drops below [warningTime]. */
    @JvmField var warningActive = false
    @JvmField var warningTime = -1f

    val timeLeft: Float
        get() {
            val t = triggerTimer ?: return 0f
            return if (t.currTime >= t.currGoal) 0f else t.currGoal - t.currTime
        }

    private fun rand(span: Int): Int = NativeRandom.next31() % span

    /** TriggeredEvent::Reset: the goals, drawn in HS's order. */
    fun reset(game: InGameState) {
        val d = def
        triggerTimer?.let { timer ->
            var goal = d.triggerMinTime
            if (d.triggerMaxTime > d.triggerMinTime) {
                val minTime = (d.triggerMinTime * 1000f).toInt()
                val maxTime = (d.triggerMaxTime * 1000f).toInt()
                goal = (minTime + rand(maxTime - minTime + 1)) / 1000f
            }
            timer.currTime = 0f
            timer.currGoal = goal
            timer.running = true
            timerSoundIndex = 0
            while (timerSoundIndex < d.timerSounds.size) {
                if (goal > d.timerSounds[timerSoundIndex].first) break
                timerSoundIndex++
            }
        }

        triggerJumps = if (d.triggerMaxJumps > d.triggerMinJumps) d.triggerMinJumps + rand(d.triggerMaxJumps - d.triggerMinJumps + 1)
        else d.triggerMinJumps

        triggerPlayerHull = -1
        if (d.minPlayerHull > -1) {
            triggerPlayerHull = maxOf(triggerPlayerHull,
                if (d.maxPlayerHull > d.minPlayerHull) d.minPlayerHull + rand(d.maxPlayerHull - d.minPlayerHull + 1) else d.minPlayerHull)
        }

        val worldLevel = HsTriggeredEvents.worldLevel(game)
        triggerEnemyHull = -1
        if (d.minEnemyHull > -1) {
            val scaling = (worldLevel * d.enemyHullScaling).toInt()
            triggerEnemyHull = maxOf(triggerEnemyHull,
                if (d.maxEnemyHull > d.minEnemyHull) d.minEnemyHull + rand(d.maxEnemyHull - d.minEnemyHull + 1) + scaling
                else d.minEnemyHull + scaling)
        }

        triggerPlayerDamage = -1
        if (d.minPlayerDamage > -1) {
            val ship = game.player
            if (ship != null) {
                currentPlayerHull = ship.health
                triggerPlayerDamage = if (d.maxPlayerDamage > d.minPlayerDamage) d.minPlayerDamage + rand(d.maxPlayerDamage - d.minPlayerDamage + 1)
                else d.minPlayerDamage
            }
        }
        triggerEnemyDamage = -1
        if (d.minEnemyDamage > -1) {
            val ship = game.enemy
            if (ship != null) {
                currentEnemyHull = ship.health
                val scaling = (worldLevel * d.enemyDamageScaling).toInt()
                triggerEnemyDamage = if (d.maxEnemyDamage > d.minEnemyDamage) d.minEnemyDamage + rand(d.maxEnemyDamage - d.minEnemyDamage + 1) + scaling
                else d.minEnemyDamage + scaling
            }
        }

        triggerPlayerCrew = -1
        if (d.minPlayerCrew > -1) {
            triggerPlayerCrew = maxOf(triggerPlayerCrew,
                if (d.maxPlayerCrew > d.minPlayerCrew) d.minPlayerCrew + rand(d.maxPlayerCrew - d.minPlayerCrew + 1) else d.minPlayerCrew)
        }
        triggerEnemyCrew = -1
        if (d.minEnemyCrew > -1) {
            triggerEnemyCrew = maxOf(triggerEnemyCrew,
                if (d.maxEnemyCrew > d.minEnemyCrew) d.minEnemyCrew + rand(d.maxEnemyCrew - d.minEnemyCrew + 1) else d.minEnemyCrew)
        }

        triggerPlayerDeaths = -1
        if (d.minPlayerDeaths > -1) {
            currentPlayerCrew = HsTriggeredEvents.playerCrew(game, d.playerDeathsCountClonebay)
            triggerPlayerDeaths = if (d.maxPlayerDeaths > d.minPlayerDeaths) d.minPlayerDeaths + rand(d.maxPlayerDeaths - d.minPlayerDeaths + 1)
            else d.minPlayerDeaths
        }
        triggerEnemyDeaths = -1
        if (d.minEnemyDeaths > -1) {
            currentEnemyCrew = HsTriggeredEvents.enemyCrew(game, d.enemyDeathsCountClonebay)
            triggerEnemyDeaths = if (d.maxEnemyDeaths > d.minEnemyDeaths) d.minEnemyDeaths + rand(d.maxEnemyDeaths - d.minEnemyDeaths + 1)
            else d.minEnemyDeaths
        }

        if (d.req.isNotEmpty()) {
            reqMinLvl = if (d.reqMinLvlSecond > d.reqMinLvlFirst) d.reqMinLvlFirst + rand(d.reqMinLvlSecond - d.reqMinLvlFirst + 1) else d.reqMinLvlFirst
            reqMaxLvl = if (d.reqMaxLvlSecond > d.reqMaxLvlFirst) d.reqMaxLvlFirst + rand(d.reqMaxLvlSecond - d.reqMaxLvlFirst + 1) else d.reqMaxLvlFirst
        }
    }

    /** TriggeredEvent::Update (one frame of game time). */
    fun update(game: InGameState, dt: Float) {
        val d = def
        triggerTimer?.let { timer ->
            timer.update(dt)
            val remaining = timer.currGoal - timer.currTime
            while (timerSoundIndex < d.timerSounds.size && remaining <= d.timerSounds[timerSoundIndex].first) {
                HsEvents.playSound(game, d.timerSounds[timerSoundIndex].second)
                timerSoundIndex++
            }
            if (d.warning != null && remaining < warningTime) warningActive = true
            if (timer.done()) triggered = true
        }
        if (triggerPlayerHull >= 0) game.player?.let { if (it.health <= triggerPlayerHull) triggered = true }
        if (triggerEnemyHull >= 0) game.enemy?.let { if (it.health <= triggerEnemyHull) triggered = true }
        if (triggerPlayerCrew >= 0 && HsTriggeredEvents.playerCrew(game, d.playerCrewCountClonebay) <= triggerPlayerCrew) triggered = true
        if (triggerEnemyCrew >= 0 && HsTriggeredEvents.enemyCrew(game, d.enemyCrewCountClonebay) <= triggerEnemyCrew) triggered = true
        if (triggerPlayerDamage >= 0) game.player?.let {
            val hull = it.health
            var change = currentPlayerHull - hull
            if (change < 0 && !d.playerCountRepairs) change = 0
            currentPlayerHull = hull
            triggerPlayerDamage -= change
            if (triggerPlayerDamage <= 0) triggered = true
        }
        if (triggerEnemyDamage >= 0) game.enemy?.let {
            val hull = it.health
            var change = currentEnemyHull - hull
            if (change < 0 && !d.enemyCountRepairs) change = 0
            currentEnemyHull = hull
            triggerEnemyDamage -= change
            if (triggerEnemyDamage <= 0) triggered = true
        }
        if (triggerPlayerDeaths >= 0) {
            val crew = HsTriggeredEvents.playerCrew(game, d.playerDeathsCountClonebay)
            var change = currentPlayerCrew - crew
            if (change < 0 && !d.playerCountNewCrew) change = 0
            currentPlayerCrew = crew
            triggerPlayerDeaths -= change
            if (triggerPlayerDeaths <= 0) triggered = true
        }
        if (triggerEnemyDeaths >= 0) {
            val crew = HsTriggeredEvents.enemyCrew(game, d.enemyDeathsCountClonebay)
            var change = currentEnemyCrew - crew
            if (change < 0 && !d.enemyCountNewCrew) change = 0
            currentEnemyCrew = crew
            triggerEnemyDeaths -= change
            if (triggerEnemyDeaths <= 0) triggered = true
        }
        if (d.req.isNotEmpty()) {
            game.player?.let { ship -> HsEvents.withCheck(5) { reqLvl = HsReq.hasEquipment(game, ship, d.req) } }
            if ((reqLvl in reqMinLvl..reqMaxLvl) != d.inverseReq) triggered = true
        }
    }

    /** TriggeredEvent::Jump: 0 = arriving at a location, 1 = the player's ship leaving. */
    fun jump(jumpType: Int) {
        if (triggerJumps > 0 && def.jumpType == jumpType) {
            if (--triggerJumps == 0) triggered = true
        }
    }

    fun save(e: Element) {
        e.setAttribute("def", def.idx.toString())
        val v = intArrayOf(seed, loops, triggerJumps, triggerPlayerHull, triggerEnemyHull, triggerPlayerCrew, triggerEnemyCrew,
            triggerPlayerDamage, triggerEnemyDamage, triggerPlayerDeaths, triggerEnemyDeaths, reqMinLvl, reqMaxLvl,
            currentPlayerHull, currentEnemyHull, currentPlayerCrew, currentEnemyCrew, reqLvl, if (triggered) 1 else 0)
        e.setAttribute("v", v.joinToString(","))
        triggerTimer?.let { e.setAttribute("timer", "${java.lang.Float.floatToRawIntBits(it.currTime)},${java.lang.Float.floatToRawIntBits(it.currGoal)}") }
    }

    /** TriggeredEvent::Load: the timer runs again (a new TimerHelper, Start-less: HS restores currTime/currGoal). */
    private fun load(e: Element) {
        val v = e.getAttributeValue("v")!!.split(',').map { it.toInt() }
        seed = v[0]; loops = v[1]; triggerJumps = v[2]; triggerPlayerHull = v[3]; triggerEnemyHull = v[4]
        triggerPlayerCrew = v[5]; triggerEnemyCrew = v[6]; triggerPlayerDamage = v[7]; triggerEnemyDamage = v[8]
        triggerPlayerDeaths = v[9]; triggerEnemyDeaths = v[10]; reqMinLvl = v[11]; reqMaxLvl = v[12]
        currentPlayerHull = v[13]; currentEnemyHull = v[14]; currentPlayerCrew = v[15]; currentEnemyCrew = v[16]
        reqLvl = v[17]; triggered = v[18] != 0
        val timer = triggerTimer
        val t = e.getAttributeValue("timer")
        if (timer != null && t != null) {
            val p = t.split(',')
            timer.currTime = java.lang.Float.intBitsToFloat(p[0].toInt())
            timer.currGoal = java.lang.Float.intBitsToFloat(p[1].toInt())
            val remaining = timer.currGoal - timer.currTime
            timerSoundIndex = 0
            while (timerSoundIndex < def.timerSounds.size) {
                if (remaining > def.timerSounds[timerSoundIndex].first) break
                timerSoundIndex++
            }
        }
    }

    companion object {
        /** The TriggeredEvent constructor: seed, loops, the timer, the req level, then Reset. */
        fun create(game: InGameState, def: TriggeredEventDefinition): HsTriggeredEvent {
            val ev = HsTriggeredEvent(def)
            if (def.seeded) ev.seed = NativeRandom.next31()
            ev.loops = if (def.maxLoops > def.minLoops) def.minLoops + NativeRandom.next31() % (def.maxLoops - def.minLoops + 1) else def.minLoops
            if (def.triggerMinTime != -1f) ev.triggerTimer = HsTriggerTimer()
            def.warning?.let { ev.warningTime = it.time }
            if (def.req.isNotEmpty()) game.player?.let { ship -> HsEvents.withCheck(5) { ev.reqLvl = HsReq.hasEquipment(game, ship, def.req) } }
            ev.reset(game)
            return ev
        }

        /** LoadAll's emplace runs the constructor (with its draws), then Load overwrites the state. */
        fun loaded(game: InGameState, def: TriggeredEventDefinition, e: Element): HsTriggeredEvent {
            val ev = create(game, def)
            ev.load(e)
            return ev
        }
    }
}

/** TriggeredEvent's statics: the running events (`eventList`, iterated in libstdc++'s order) and the frame hooks. */
object HsTriggeredEvents {
    @JvmField val eventList = HsGnuStringMap<HsTriggeredEvent>()

    /** TriggeredEventGui::reset: the boxes are rebuilt (render workstream). */
    @JvmField var guiReset = false

    /** TriggeredEvent::playerCloneCount, counted at the start of [updateAll]. */
    private var playerCloneCount = 0

    /** A loaded run's block, applied with the game at the first frame (HsSave readers get no game). */
    private var pending: Element? = null

    fun worldLevel(game: InGameState): Int = game.currentBeacon?.sector?.sectorNumber ?: 0

    private fun parser(game: InGameState): HsEventParser = game.eventManager.hs!!

    /** CrewMemberFactory::playerCrew (the player's crew wherever they are, with those waiting in the clonebay). */
    fun playerCrew(game: InGameState, clones: Boolean): Int {
        var ret = game.playerCrew.size
        if (!clones) ret -= playerCloneCount
        return ret
    }

    /** CrewMemberFactory::enemyCrew minus enemyCloneCount without [clones]. */
    fun enemyCrew(game: InGameState, clones: Boolean): Int {
        val enemy = game.enemy ?: return 0
        var count = 0
        val all = ArrayList<xyz.znix.xftl.crew.AbstractCrew>(enemy.crew)
        game.player?.let { all.addAll(it.crew) }
        for (c in all) if (c is LivingCrew && c.ownerShip === enemy) count++
        val queue = enemy.clonebay?.queue?.count { it.ownerShip === enemy } ?: 0
        count += queue
        if (!clones) count -= queue
        return count
    }

    fun clear() {
        eventList.clear()
        pending = null
        guiReset = true
        HsTriggeredGui.reset()
    }

    private fun resolvePending(game: InGameState) {
        val elem = pending ?: return
        pending = null
        val defs = parser(game).triggeredDefs
        for (e in elem.getChildren("t")) {
            val def = defs.getOrNull(e.getAttributeValue("def")?.toIntOrNull() ?: -1)
            if (def == null) {
                HS.unsupported("save", "triggeredEvents", e.getAttributeValue("name") ?: "")
                continue
            }
            eventList.emplace(def.name, HsTriggeredEvent.loaded(game, def, e))
        }
    }

    /** TriggeredEvent::NewEvent. */
    fun newEvent(game: InGameState, def: TriggeredEventDefinition) {
        val seeded = def.seeded && HsSeeds.enabled
        if (seeded) NativeRandom.seed(HsSeeds.questSeed)
        eventList.remove(def.name)
        eventList.emplace(def.name, HsTriggeredEvent.create(game, def))
        guiReset = true
        if (seeded) HsSeeds.questSeed = NativeRandom.next31()
    }

    /** TriggeredEvent::DestroyEvent. */
    fun destroyEvent(name: String) {
        if (eventList.remove(name)) guiReset = true
    }

    /** TriggeredEventModifier::ApplyModifier. */
    fun applyModifier(m: TriggeredEventModifier) {
        val ev = eventList[m.name] ?: return
        ev.triggerTimer?.let { timer ->
            var modTime = m.minTime
            if (m.maxTime > m.minTime) {
                val minT = (m.minTime * 1000f).toInt()
                val maxT = (m.maxTime * 1000f).toInt()
                modTime = (minT + NativeRandom.next31() % (maxT - minT + 1)) / 1000f
            }
            timer.currGoal += modTime
            if (timer.currGoal < timer.currTime) timer.currGoal = timer.currTime
        }
        if (ev.triggerJumps >= 0) {
            var modJumps = m.minJumps
            if (m.maxJumps > m.minJumps) modJumps = m.minJumps + NativeRandom.next31() % (m.maxJumps - m.minJumps + 1)
            ev.triggerJumps = maxOf(0, ev.triggerJumps + modJumps)
        }
    }

    /** An event's or a ship's clear/new/modify lists (ModifyResources and CreateShip, in that order). */
    fun apply(game: InGameState, clear: List<String>, defs: List<Int>, modifiers: List<TriggeredEventModifier>) {
        for (name in clear) destroyEvent(name)
        val all = parser(game).triggeredDefs
        for (idx in defs) newEvent(game, all[idx])
        for (m in modifiers) applyModifier(m)
    }

    /** TriggeredEvent::UpdateAll (WorldManager::OnLoop, after the original; game time only). */
    fun updateAll(game: InGameState, dt: Float, choiceBoxOpen: Boolean) {
        resolvePending(game)
        playerCloneCount = game.player?.clonebay?.queue?.count { it.ownerShip === game.player } ?: 0
        val enemy = game.enemy
        val hostile = enemy != null && game.isEnemyHostile
        var n = eventList.first
        while (n != null) {
            val ev = n.value
            if (ev.def.thisFight) {
                if (hostile && !enemy!!.isDead && !game.isEnemyJumping) ev.update(game, dt)
                if (!hostile && !choiceBoxOpen) {
                    n = eventList.erase(n)
                    guiReset = true
                } else {
                    n = n.next()
                }
            } else {
                ev.update(game, dt)
                n = n.next()
            }
        }
        if (HsEvents.deathEventQueue.isNotEmpty() && !hostile && !choiceBoxOpen) {
            HsEvents.deathEventQueue.removeAll { it.thisFight }
        }
    }

    /** TriggeredEvent::JumpAll. */
    fun jumpAll(game: InGameState, jumpType: Int) {
        resolvePending(game)
        var n = eventList.first
        while (n != null) {
            n.value.jump(jumpType)
            if (n.value.def.clearOnJump) {
                n = eventList.erase(n)
                guiReset = true
            } else {
                n = n.next()
            }
        }
    }

    /** TriggeredEvent::TriggerCheck: triggered events load (UpdateLocation of GetBaseEvent) until a box opens. */
    fun triggerCheck(game: InGameState, host: HsFlow.Host, choiceBoxOpen: () -> Boolean) {
        resolvePending(game)
        var n = eventList.first
        while (n != null) {
            val ev = n.value
            if (!ev.triggered) {
                n = n.next()
                continue
            }
            val eventName = ev.def.event
            val seed = ev.seed
            val level = game.eventCreator.currentLevel()
            ev.warningActive = false
            if (--ev.loops <= 0) {
                n = eventList.erase(n)
                guiReset = true
            } else {
                ev.triggered = false
                ev.reset(game)
                n = n.next()
            }
            HsFlow.getBaseEvent(game, eventName, level, false, seed)?.let { host.hsUpdateLocation(it) }
            if (choiceBoxOpen()) break
        }
    }

    // ------------------------------------------------------------------ persistence (part of the events run block)

    fun write(elem: Element) {
        pending?.let { p -> for (e in p.getChildren("t")) elem.addContent(e.clone()); return }
        var n = eventList.first
        while (n != null) {
            val e = Element("t").setAttribute("name", n.key)
            n.value.save(e)
            elem.addContent(e)
            n = n.next()
        }
    }

    /** LoadAll: clears, then emplaces in the saved order (which can give another iteration order, as in HS). */
    fun read(elem: Element?) {
        clear()
        if (elem != null && elem.children.isNotEmpty()) pending = elem.clone()
    }
}
